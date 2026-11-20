package com.github.vgerya.gitlabpipelines.plugin.service

import com.intellij.util.messages.Topic

/** Published on the application message bus from a background thread; marshal to EDT yourself. */
fun interface PipelinesListener {
    fun snapshotChanged(snapshot: Snapshot)

    companion object {
        val TOPIC: Topic<PipelinesListener> = Topic.create("GitLabPipelines", PipelinesListener::class.java)
    }
}
