package com.devtool.plugin.loghub;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MicroserviceLogToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MicroserviceLogPanel panel = new MicroserviceLogPanel(project, toolWindow.getDisposable());
        ContentFactory contentFactory = resolveContentFactory();
        Content content = contentFactory.createContent(panel.getRootComponent(), "全量日志 & 报错总控", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    private static ContentFactory resolveContentFactory() {
        try {
            ContentFactory service = ApplicationManager.getApplication().getService(ContentFactory.class);
            if (service != null) {
                return service;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method m = ContentFactory.class.getMethod("getInstance");
            return (ContentFactory) m.invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            Field f = ContentFactory.class.getField("SERVICE");
            Object serviceObj = f.get(null);
            Method m = serviceObj.getClass().getMethod("getInstance");
            return (ContentFactory) m.invoke(serviceObj);
        } catch (Throwable e) {
            throw new IllegalStateException("Unable to obtain ContentFactory", e);
        }
    }
}
