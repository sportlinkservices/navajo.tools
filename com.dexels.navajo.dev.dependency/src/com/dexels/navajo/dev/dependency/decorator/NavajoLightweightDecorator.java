package com.dexels.navajo.dev.dependency.decorator;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dexels.navajo.dev.dependency.Activator;
import com.dexels.navajo.dev.dependency.model.EclipseDependencyAnalyzer;


public class NavajoLightweightDecorator implements ILightweightLabelDecorator {
    private final static Logger logger = LoggerFactory.getLogger(NavajoLightweightDecorator.class);
    
    private EclipseDependencyAnalyzer depAnalyzer;
    private Map<IResource, Boolean> cacheMap;

    public NavajoLightweightDecorator() {
        depAnalyzer = EclipseDependencyAnalyzer.getInstance();
        resetCache();
    }
    
    public void resetCache() {
        cacheMap = new HashMap<IResource, Boolean>();
    }

    private String getScriptFromFilename(String filename) {
        String scriptFilePath = null;
        String script = null;
        
        try {
            if (filename.indexOf("workflows") > 0) {
                scriptFilePath = filename.split("workflows")[1];
            } else if (filename.indexOf("tipi") > 0) {
                scriptFilePath = filename.split("tipi")[1];
            } else if (filename.indexOf("article") > 0) {
                scriptFilePath = filename.split("article")[1];
            } else if (filename.indexOf("scripts") > 0) {
                scriptFilePath = filename.split("scripts")[1];
            } else if (filename.endsWith("tasks.xml")) {
                // Tasks file as a bit special, since they don't have their own
                // directory really. Hence we simulate this
                String pattern = Pattern.quote(File.separator);
                String[] filenameParts = filename.split(pattern);
                String tenant = filenameParts[filenameParts.length - 3];
                scriptFilePath = File.separator + tenant + File.separator + "tasks.xml";
            } else {
                return "";
            }
        } catch (Exception e) {
            logger.error("Exception on retrieving scriptname from filename {} : {} ", filename, e);
            return "";
        }
    
        
        if (scriptFilePath.length() < 1 || scriptFilePath.indexOf(".") < 1) {
            return "";
        }
        script = scriptFilePath.substring(1, scriptFilePath.indexOf("."));

        // Replace win32 slashes to be consistent with Navajo script slashes
        return script.replace("\\", "/");
    }

    @Override
    public void decorate(Object element, IDecoration decoration) {
        if (! (element instanceof IResource)) {
            return;
        }
       
        if (element instanceof IFile) {
            IFile file = (IFile) element;
            if (fileContainsBrokenDependencies( file)) {
                decoration.addOverlay(Activator.getImageDescriptor("icons/broken.gif"), IDecoration.BOTTOM_LEFT);
            }
        } else if (element instanceof IFolder) {
            IFolder folder = (IFolder) element;
            try {
                if (folderContainsBrokenDependencies(folder)) {
                    decoration.addOverlay(Activator.getImageDescriptor("icons/broken.gif"), IDecoration.BOTTOM_LEFT);
                }
            } catch (CoreException e) {
                
            }
        }
    }

    private boolean fileContainsBrokenDependencies(IFile file) {
        String filePath = file.getLocation().toOSString();
        if (cacheMap.get(file) != null) {
            return cacheMap.get(file);
        }
        
        if ((filePath.contains("scripts") || filePath.contains("workflows") || filePath.contains("article") || filePath.contains("tipi") || filePath.contains("tasks"))
                && filePath.contains(".xml")) {
            if (depAnalyzer.containsBrokenDependencies(getScriptFromFilename(filePath))) {
                cacheMap.put(file, true);
                return true;
            }
        }
        cacheMap.put(file, false);
        return false;
    }
    
    private boolean folderContainsBrokenDependencies(IFolder folder) throws CoreException {
        if (cacheMap.get(folder) != null) {
            return cacheMap.get(folder);
        }
        
        for (IResource folderMemeber : folder.members()) {
            if (folderMemeber instanceof IFile) {
               if (fileContainsBrokenDependencies((IFile) folderMemeber)) {
                   cacheMap.put(folder, true);
                   return true;
               }
            } else if (folderMemeber instanceof IFolder) {
                if (folderContainsBrokenDependencies((IFolder) folderMemeber)) {
                    cacheMap.put(folder, true);
                    return true;
                }
            }
        }
        cacheMap.put(folder, false);
        return false;
    }
    

    @Override
    public void dispose() {
    }

    @Override
    public boolean isLabelProperty(Object element, String property) {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public void addListener(ILabelProviderListener listener) {
    }

    @Override
    public void removeListener(ILabelProviderListener listener) {
       
    }

}
