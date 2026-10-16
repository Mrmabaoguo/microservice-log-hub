package com.devtool.plugin.loghub;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class MicroserviceLogToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MicroserviceLogPanel panel = new MicroserviceLogPanel(project, toolWindow.getDisposable());
        Content content = ContentFactory.getInstance().createContent(panel.getRootComponent(), "全量日志 & 报错总控", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
