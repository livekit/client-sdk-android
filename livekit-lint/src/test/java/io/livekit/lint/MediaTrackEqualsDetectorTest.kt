@file:Suppress("UnstableApiUsage", "NewObjectEquality")

package io.livekit.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestLintTask
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
          import org.webrtc.MediaStreamTrack;
          
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
          import org.webrtc.MediaStreamTrack;
          
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
}

fun TestLintTask.mediaStreamTrack(): TestLintTask {
    return this.files(
        java(
            """
        package org.webrtc;
        
        class MediaStreamTrack {
            int getId(){
                return 0;
            }        
        }
    """
        ).indented()
    )
}

fun Any.mediaStreamTrack(): TestFile {
    return java(
        """
        package org.webrtc;
        
        class MediaStreamTrack {
            int getId(){
                return 0;
            }        
        }
    """
    ).indented()
}