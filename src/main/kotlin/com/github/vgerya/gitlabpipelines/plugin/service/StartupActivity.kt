package com.github.vgerya.gitlabpipelines.plugin.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        PipelinesService.getInstance().ensureStarted()
    }
}
