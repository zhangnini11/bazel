// Copyright 2017 The Bazel Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.testing.junit.runner;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.testing.junit.runner.internal.StackTraces;
import com.google.testing.junit.runner.junit4.JUnit4InstanceModules.Config;
import com.google.testing.junit.runner.junit4.JUnit4InstanceModules.SuiteClass;
import com.google.testing.junit.runner.junit4.JUnit4Runner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * For now the same as {@link BazelTestRunner} but intended to be a testbed to try out new features,
 * without breeaking existing tests.
 */
public class ExperimentalTestRunner {
  /**
   * If no arguments are passed on the command line, use this System property to
   * determine which test suite to run.
   */
  static final String TEST_SUITE_PROPERTY_NAME = "bazel.test_suite";

  private ExperimentalTestRunner() {
    // utility class; should not be instantiated
  }

  /**
   * Takes as arguments the classes or packages to test.
   *
   * <p>To help just run one test or method in a suite, the test suite
   * may be passed in via system properties (-Dbazel.test_suite).
   * An empty args parameter means to run all tests in the suite.
   * A non-empty args parameter means to run only the specified tests/methods.
   *
   * <p>Return codes:
   * <ul>
   * <li>Test runner failure, bad arguments, etc.: exit code of 2</li>
   * <li>Normal test failure: exit code of 1</li>
   * <li>All tests pass: exit code of 0</li>
   * </ul>
   */
  public static void main(String[] args) {
    PrintStream stderr = System.err;

    String suiteClassName = System.getProperty(TEST_SUITE_PROPERTY_NAME);

    if ("true".equals(System.getenv("PERSISTENT_TEST_RUNNER"))) {
      System.exit(runPersistentTestRunner(suiteClassName));
    }

    System.out.println("WARNING: RUNNING EXPERIMENTAL TEST RUNNER");

    if (!checkTestSuiteProperty(suiteClassName)) {
      System.exit(2);
    }

    int exitCode = runTestsInSuite(suiteClassName, args);

    System.err.printf("%nExperimentalTestRunner exiting with a return value of %d%n", exitCode);
    System.err.println("JVM shutdown hooks (if any) will run now.");
    System.err.println("The JVM will exit once they complete.");
    System.err.println();

    printStackTracesIfJvmExitHangs(stderr);

    DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    Date shutdownTime = new Date();
    String formattedShutdownTime = format.format(shutdownTime);
    System.err.printf("-- JVM shutdown starting at %s --%n%n", formattedShutdownTime);
    System.exit(exitCode);
  }

  /**
   * Ensures that the bazel.test_suite in argument is not {@code null} or print error and
   * explanation.
   *
   * @param testSuiteProperty system property to check
   */
  private static boolean checkTestSuiteProperty(String testSuiteProperty) {
    if (testSuiteProperty == null) {
      System.err.printf(
          "Error: The test suite Java system property %s is required but missing.%n",
          TEST_SUITE_PROPERTY_NAME);
      System.err.println();
      System.err.println("This property is set automatically when running with Bazel like such:");
      System.err.printf("  java -D%s=[test-suite-class] %s%n",
          TEST_SUITE_PROPERTY_NAME, ExperimentalTestRunner.class.getName());
      System.err.printf("  java -D%s=[test-suite-class] -jar [deploy-jar]%n",
          TEST_SUITE_PROPERTY_NAME);
      System.err.println("E.g.:");
      System.err.printf("  java -D%s=org.example.testing.junit.runner.SmallTests %s%n",
          TEST_SUITE_PROPERTY_NAME, ExperimentalTestRunner.class.getName());
      System.err.printf("  java -D%s=org.example.testing.junit.runner.SmallTests "
              + "-jar SmallTests_deploy.jar%n",
          TEST_SUITE_PROPERTY_NAME);
      return false;
    }
    return true;
  }

  private static int runTestsInSuite(String suiteClassName, String[] args) {
    Class<?> suite = getTestClass(suiteClassName);

    if (suite == null) {
      // No class found corresponding to the system property passed in from Bazel
      if (args.length == 0 && suiteClassName != null) {
        System.err.printf("Class not found: [%s]%n", suiteClassName);
        return 2;
      }
    }

    // TODO(kush): Use a new classloader for the following instantiation.
    JUnit4Runner runner =
        JUnit4Bazel.builder()
            .suiteClass(new SuiteClass(suite))
            .config(new Config(args))
            .build()
            .runner();
    return runner.run().wasSuccessful() ? 0 : 1;
  }

  private static int runPersistentTestRunner(String suiteClassName) {
    PrintStream originalStdOut = System.out;
    PrintStream originalStdErr = System.err;

    while (true) {
      try {
        WorkRequest request = WorkRequest.parseDelimitedFrom(System.in);

        if (request == null) {
          break;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true);
        System.setOut(ps);
        System.setErr(ps);

        String[] arguments = request.getArgumentsList().toArray(new String[0]);
        int exitCode = -1;
        try {
          exitCode = runTestsInSuite(suiteClassName, arguments);
        } finally {
          System.setOut(originalStdOut);
          System.setErr(originalStdErr);
        }

        WorkResponse response =
            WorkResponse.newBuilder().setOutput(baos.toString()).setExitCode(exitCode).build();
        response.writeDelimitedTo(System.out);
        System.out.flush();

      } catch (IOException e) {
        e.printStackTrace();
        return 1;
      }
    }
    return 0;
  }

  private static Class<?> getTestClass(String name) {
    if (name == null) {
      return null;
    }

    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  /**
   * Prints out stack traces if the JVM does not exit quickly. This can help detect shutdown hooks
   * that are preventing the JVM from exiting quickly.
   *
   * @param out Print stream to use
   */
  private static void printStackTracesIfJvmExitHangs(final PrintStream out) {
    Thread thread = new Thread(new Runnable() {
      @Override
      public void run() {
        sleepUninterruptibly(5);
        out.println("JVM still up after five seconds. Dumping stack traces for all threads.");
        StackTraces.printAll(out);
      }
    }, "ExperimentalTestRunner: Print stack traces if JVM exit hangs");

    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Invokes SECONDS.{@link TimeUnit#sleep(long) sleep(sleepForSeconds)} uninterruptibly.
   */
  private static void sleepUninterruptibly(long sleepForSeconds) {
    boolean interrupted = false;
    try {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(sleepForSeconds);
      while (true) {
        try {
          // TimeUnit.sleep() treats negative timeouts just like zero.
          TimeUnit.NANOSECONDS.sleep(end - System.nanoTime());
          return;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
