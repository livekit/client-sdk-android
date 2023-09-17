package io.livekit.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue
import com.google.auto.service.AutoService

@Suppress("UnstableApiUsage", "unused")
@AutoService(value = [IssueRegistry::class])
class IssueRegistry : IssueRegistry() {

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "LiveKit",
        identifier = "io.livekit.android",
        feedbackUrl = "https://github.com/livekit/client-sdk-android",
    )

    override val issues: List<Issue>
        get() = listOf(MediaTrackEqualsDetector.ISSUE, FlowDelegateUsageDetector.ISSUE)
}
