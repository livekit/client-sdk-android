@file:Suppress("UnstableApiUsage", "NewObjectEquality")

package io.livekit.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest.bytes
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class FlowDelegateUsageDetectorTest {
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
    fun mediaTrackEquals() {
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
              a.equals(b);
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