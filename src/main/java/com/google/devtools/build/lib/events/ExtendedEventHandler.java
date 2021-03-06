// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.events;

/**
 * Interface for reporting events during the build. It extends the {@link EventHandler} by also
 * allowing posting more structured information.
 */
public interface ExtendedEventHandler extends EventHandler {

  /** Interface for declaring events that can be posted via the extended event handler */
  public interface Postable {}

  /** Post an postable object with more refined information about an important build event */
  void post(Postable obj);

  /**
   * Interface for declaring postable events that report about progress (as oposed to success or
   * failure) and hence should not be stored and replayed.
   */
  public interface ProgressLike extends Postable {}
}
