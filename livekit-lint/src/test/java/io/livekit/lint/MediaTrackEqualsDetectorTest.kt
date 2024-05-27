/*
 * Copyright 2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UnstableApiUsage", "NewObjectEquality")

package io.livekit.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class MediaTrackEqualsDetectorTest {
    @Test
    fun objectEquals() {
        lint()
            .allowMissingSdk()
            .files(
                java(
                    """
          package foo;

          class Example {
            public boolean foo() {
              Object a = new Object();
              Object b = new Object();
              return a.equals(b);
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun objectEqualityOperator() {
        lint()
            .allowMissingSdk()
            .files(
                java(
                    """
          package foo;

          class Example {
            public boolean foo() {
              Object a = new Object();
              Object b = new Object();
              return a == b;
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun badMediaTrackEquals() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                java(
                    """
          package foo;
          import livekit.org.webrtc.MediaStreamTrack;

          class Example {
            public boolean foo() {
              MediaStreamTrack a = new MediaStreamTrack();
              MediaStreamTrack b = new MediaStreamTrack();
              return a.equals(b);
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    @Test
    fun mediaTrackEqualityOperator() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                java(
                    """
          package foo;
          import livekit.org.webrtc.MediaStreamTrack;

          class Example {
            public boolean foo() {
              ABC a = new ABC();
              MediaStreamTrack b = new MediaStreamTrack();
              return a == b;
            }
            public boolean equals(Object o){
            return false;
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    @Test
    fun properMediaTrackEquality() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                java(
                    """
          package foo;

          class Example {
            public boolean foo() {
              MediaStreamTrack a = new MediaStreamTrack();
              MediaStreamTrack b = new MediaStreamTrack();
              return a.getId() == b.getId();
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun kotlinMediaTrackEqualityOperator() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                kotlin(
                    """
          package foo
          import livekit.org.webrtc.MediaStreamTrack

          class Example {
            fun foo() : Boolean {
              val a = MediaStreamTrack()
              val b = MediaStreamTrack()
              return a == b;
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    @Test
    fun kotlinMediaTrackIdentityEqualityOperator() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                kotlin(
                    """
          package foo
          import livekit.org.webrtc.MediaStreamTrack

          class Example {
            fun foo() : Boolean {
              val a = MediaStreamTrack()
              val b = MediaStreamTrack()
              return a === b
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    @Test
    fun kotlinMediaTrackEquals() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                kotlin(
                    """
          package foo
          import livekit.org.webrtc.MediaStreamTrack

          class Example {
            fun foo() : Boolean {
              val a = MediaStreamTrack()
              val b = MediaStreamTrack()
              return a.equals(b)
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    @Test
    fun kotlinProperMediaTrackEquality() {
        lint()
            .allowMissingSdk()
            .files(
                mediaStreamTrack(),
                kotlin(
                    """
          package foo
          import livekit.org.webrtc.MediaStreamTrack

          class Example {
            fun foo() : Boolean {
              val a = MediaStreamTrack()
              val b = MediaStreamTrack()
              return a.id() == b.id()
            }
          }"""
                ).indented()
            )
            .issues(MediaTrackEqualsDetector.ISSUE)
            .run()
            .expectClean()
    }
}

fun mediaStreamTrack(): TestFile {
    return java(
        """
        package livekit.org.webrtc;

        class MediaStreamTrack {
            int getId(){
                return 0;
            }
        }
    """
    ).indented()
}
