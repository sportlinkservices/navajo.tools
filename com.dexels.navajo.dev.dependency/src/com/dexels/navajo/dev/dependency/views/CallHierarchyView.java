package com.dexels.navajo.dev.dependency.views;

import java.io.File;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.action.*;
import org.eclipse.ui.*;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.SWT;

import com.dexels.navajo.dev.dependency.Activator;

public class CallHierarchyView extends ViewPart implements ISelectionListener {

    /**
     * The ID of the view as specified by the extension.
     */
    public static final String ID = "com.dexels.navajo.dev.dependency.views.CallHierarchyView";

    private ViewContentProvider viewProvider;
    private TreeViewer viewer;
    private Action callerHierarchy;
    private Action doubleClickAction;
    private Action cancelAction;
    private MyResourceChangeReporter resourceListener;

    private Action rebuildAction;

    private Action calleeHierarchy;

    /*
     * The content provider class is responsible for providing objects to the
     * view. It can wrap existing objects in adapters or simply return objects
     * as-is. These objects may be sensitive to the current input of the view,
     * or ignore it and always show the same content (like Task List, for
     * example).
     */

    class NameSorter extends ViewerSorter {
    }

    /**
     * The constructor.
     */
    public CallHierarchyView() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        window.getSelectionService().addSelectionListener(this);

        // Register to get notifications of changed files
        resourceListener = new MyResourceChangeReporter();
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
        
        // Register to get notifications of opened files
        window.getPartService().addPartListener(resourceListener);
    }

    public void dispose() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        window.getSelectionService().removeSelectionListener(this);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        window.getPartService().removePartListener(resourceListener);
    }

    /**
     * This is a callback that will allow us to create the viewer and initialize
     * it.
     */
    public void createPartControl(Composite parent) {

        viewProvider = new ViewContentProvider(getViewSite(), this);
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.setLabelProvider(new ViewLabelProvider());
        viewer.setContentProvider(viewProvider);
        viewer.setSorter(new NameSorter());
        viewer.setInput(getViewSite());

        // Create the help context id for the viewer's control
        PlatformUI.getWorkbench().getHelpSystem().setHelp(viewer.getControl(), "com.dexels.navajo.dev.dependency.viewer");
        makeActions();
        hookContextMenu();
        hookDoubleClickAction();
        contributeToActionBars();
    }

    private void hookContextMenu() {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            public void menuAboutToShow(IMenuManager manager) {
                CallHierarchyView.this.fillContextMenu(manager);
            }
        });
        Menu menu = menuMgr.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuMgr, viewer);
    }

    private void contributeToActionBars() {
        IActionBars bars = getViewSite().getActionBars();
        fillLocalPullDown(bars.getMenuManager());
        fillLocalToolBar(bars.getToolBarManager());
    }

    private void fillLocalPullDown(IMenuManager manager) {
        manager.add(callerHierarchy);
        manager.add(calleeHierarchy);
        manager.add(new Separator());
    }

    private void fillContextMenu(IMenuManager manager) {
       

        manager.add(new Separator());
       // drillDownAdapter.addNavigationActions(manager);
        // Other plug-ins can contribute there actions here
        manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
    }

    private void fillLocalToolBar(IToolBarManager manager) {
        manager.add(callerHierarchy);
        manager.add(calleeHierarchy);
        manager.add(rebuildAction);
        //manager.add(cancelAction);
        manager.add(new Separator());
        //drillDownAdapter.addNavigationActions(manager);
    }

    private void makeActions() {
        callerHierarchy = new Action() {
            public void run() {
                viewProvider.setReverseMode(true);
                callerHierarchy.setChecked(true);
                calleeHierarchy.setChecked(false);
                updateRoot((TreeParent)viewProvider.getRoot());                
            }
        };
        callerHierarchy.setText("Show Caller Hierarchy");
        callerHierarchy.setToolTipText("Show Caller Hierarchy");
        callerHierarchy.setImageDescriptor(Activator.getImageDescriptor("icons/callers.gif"));
        callerHierarchy.setChecked(true);
        
        calleeHierarchy = new Action() {
            public void run() {
                viewProvider.setReverseMode(false);
                callerHierarchy.setChecked(false);
                calleeHierarchy.setChecked(true);
                updateRoot((TreeParent)viewProvider.getRoot());                
            }
        };
        calleeHierarchy.setText("Show Callee Hierarchy");
        calleeHierarchy.setToolTipText("Show Callee Hierarchy");
        calleeHierarchy.setImageDescriptor(Activator.getImageDescriptor("icons/callees.gif"));
        calleeHierarchy.setChecked(false);
        
        rebuildAction = new Action() {
            public void run() {
                rebuild();             
            }
        };
        rebuildAction.setText("Rebuild dependency tree");
        rebuildAction.setToolTipText("Rebuild dependency tree");
        rebuildAction.setImageDescriptor(Activator.getImageDescriptor("icons/refresh.gif"));
        
        cancelAction = new Action() {
            public void run() {
                cancelJob();             
            }
        };
        cancelAction.setText("Cancel current search");
        cancelAction.setToolTipText("Cancel current search");
        cancelAction.setImageDescriptor(Activator.getImageDescriptor("icons/cancel.gif"));

        doubleClickAction = new Action() {
            public void run() {
                ISelection selection = viewer.getSelection();
                Object obj = ((IStructuredSelection) selection).getFirstElement();

                if (obj instanceof TreeObject) {
                    TreeObject treeObj = (TreeObject) obj;
                    File fileToOpen = new File(treeObj.getFilePath());
                    if (fileToOpen.exists() && fileToOpen.isFile()) {
                        IFileStore fileStore = EFS.getLocalFileSystem().getStore(fileToOpen.toURI());
                        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();

                        try {
                            IDE.openEditorOnFileStore(page, fileStore);
                        } catch (PartInitException e) {
                            // Put your exception handler here if you wish to
                        }
                    }

                }

            }
        };
    }
    

    private void hookDoubleClickAction() {
        viewer.addDoubleClickListener(new IDoubleClickListener() {
            public void doubleClick(DoubleClickEvent event) {
                doubleClickAction.run();
            }
        });
    }

//    private void showMessage(String message) {
//        MessageDialog.openInformation(viewer.getControl().getShell(), "Sample View", message);
//    }

    
    /**
     * Passing the focus request to the viewer's control.
     */
    public void setFocus() {
        viewProvider.setRoot((TreeParent) viewProvider.getRoot());

        Display.getDefault().asyncExec(new Runnable() {
            public void run() {
                viewer.refresh(getViewSite());
                viewer.getControl().setFocus();
            }
        });
       
    }

    @Override
    public void selectionChanged(IWorkbenchPart sourcepart, ISelection selection) {
        if (sourcepart != CallHierarchyView.this && selection instanceof IStructuredSelection) {
            Object selectedObject = ((IStructuredSelection) selection).getFirstElement();

            if (selectedObject instanceof IFile) {
                String filePath = ((IFile) selectedObject).getLocation().toString();
                if (filePath.contains("scripts") || filePath.contains("workflows")) {
                    if (viewProvider.getRoot() == null || !viewProvider.getRoot().getFilePath().equals(filePath)) {
                        updateRoot(new TreeParent(filePath, 0));
                    }
                    return;
                }

            }
            updateRoot(viewProvider.getAbsoluteRoot());

        }
    }

    
    
    private void updateRoot(final TreeParent treeParent) {
        viewProvider.setRoot(treeParent);
        
        Display.getDefault().asyncExec(new Runnable() {
            public void run() {
                viewer.refresh();
                viewer.refresh(getViewSite());
                viewer.expandToLevel(2);
            }
        });
    }

    private void rebuild() {
        TreeParent root = (TreeParent) viewProvider.getRoot();
        viewProvider.rebuild();
        updateRoot(root);
    }

    private void cancelJob() {
        //
    }
    
    class MyResourceChangeReporter implements IResourceChangeListener, IPartListener {

        @Override
        public void resourceChanged(IResourceChangeEvent event) {
            try {
                event.getDelta().accept(new DeltaUpdater());
            } catch (CoreException e) {
                // Something weird happened - lets just leave it at this
            }
        }

        @Override
        public void partActivated(IWorkbenchPart e) {
            if (e instanceof IEditorPart) {
                updateRootFromWorkbench(e);

            }
        }

        @Override
        public void partBroughtToTop(IWorkbenchPart e) {

            if (e instanceof IEditorPart) {
                updateRootFromWorkbench(e);
            }
        }

        @Override
        public void partClosed(IWorkbenchPart arg0) {
        }

        @Override
        public void partDeactivated(IWorkbenchPart arg0) {
        }

        @Override
        public void partOpened(IWorkbenchPart e) {
            if (e instanceof IEditorPart) {
                updateRootFromWorkbench(e);
            }

        }

        private void updateRootFromWorkbench(IWorkbenchPart e) {
            IEditorInput input = ((IEditorPart) e).getEditorInput();
            if (input instanceof FileEditorInput) {
                FileEditorInput fileInput = (FileEditorInput) input;
                String filePath = fileInput.getFile().getLocation().toString();
                if (filePath.contains("scripts") || filePath.contains("workflows")) {
                    if (viewProvider.getRoot() == null || ! viewProvider.getRoot().getFilePath().equals(filePath)) {
                        updateRoot(new TreeParent(filePath, 0));
                    }
                    return;
                }
                updateRoot(viewProvider.getAbsoluteRoot());
                
            }
        }

    }
    
    class DeltaUpdater implements IResourceDeltaVisitor {
        public boolean visit(IResourceDelta delta) {
            IResource res = delta.getResource();
            String filePath = res.getLocation().toFile().getAbsolutePath();
            if (filePath.indexOf("scripts") > 0 && filePath.indexOf(".xml") > 0) {

                switch (delta.getKind()) {
                case IResourceDelta.ADDED:
                    viewProvider.updateResource(filePath);
                    refreshRoot();
                    break;
                case IResourceDelta.REMOVED:
                    viewProvider.removeResource(filePath);
                    refreshRoot();
                    break;
                case IResourceDelta.CHANGED:
                    viewProvider.updateResource(filePath);
                    refreshRoot();

                    break;
                }
            }
            return true;
        }

        private void refreshRoot() {
            TreeObject o = viewProvider.getRoot();
            if (o != null) {
                updateRoot(new TreeParent(o.getFilePath(), 0));
            }

        }
    }

}
