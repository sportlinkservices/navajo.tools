package com.dexels.navajo.dev.dependency.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dexels.navajo.dependency.Dependency;
import com.dexels.navajo.dependency.DependencyAnalyzer;
import com.dexels.navajo.dev.dependency.Activator;
import com.dexels.navajo.dev.dependency.preferences.NavajoDependencyPreferences;
import com.dexels.navajo.dev.dependency.views.ViewContentProvider;

public class EclipseDependencyAnalyzer extends DependencyAnalyzer {
    private static final int JOB_START_DELAY = 1000;

    private static class Holder {
        static final EclipseDependencyAnalyzer INSTANCE = new EclipseDependencyAnalyzer();
    }
    
    public static final String INIT_JOB_NAME = "Calculating Navajo Dependencies";
    public static final String EXT_DEPS_JOB_NAME = "Calculating External Project Dependencies";
    
    private final static Logger logger = LoggerFactory.getLogger(EclipseDependencyAnalyzer.class);
    
    private String rootFolder;
    private Job initializeJob;
    private Job extProjectJob;
    private CodeSearch codeSearch;
    private boolean initialized = false;

    protected Map<String, Map<String, List<Dependency>>> externaldependencies;
    protected Map<String, Map<String, List<Dependency>>> reverseExternaldependencies;

    private Object externalLock = new Object();
    private Object externalProjLock = new Object();

    public EclipseDependencyAnalyzer() {
        precompiler = new EclipseTslPreCompiler();
        codeSearch = new CodeSearch();
        initialize();
    }
    
    public EclipseDependencyAnalyzer(Boolean init) {
        codeSearch = new CodeSearch();
        if (init) {
            precompiler = new EclipseTslPreCompiler();
           
        }
    }

    public static EclipseDependencyAnalyzer getInstance() {
        return Holder.INSTANCE;
    }

    public void initialize(ViewContentProvider callback) {
        if (initialized || initializeJob != null) {
            return;
        }
        rebuild(callback);

    }

    public synchronized void rebuild(final ViewContentProvider callback) {
        // Resetting the following values will trigger a rebuild
        try {
            IProject scriptsProject = NavajoDependencyPreferences.getInstance().getScriptsProject();
            Activator.getDefault().log("ScriptProjects = " + scriptsProject.toString());
            rootFolder = scriptsProject.getLocation().toOSString() + File.separator;
        } catch (Exception e) {
            Activator.getDefault().log("Error on getting scripts folder! " + e);
            return;
        }

        if (initializeJob != null) {
            initializeJob.cancel();
        }
        initialized = false;
        initializeJob = null;
        externaldependencies = new HashMap<String, Map<String, List<Dependency>>>();
        reverseExternaldependencies = new HashMap<String, Map<String, List<Dependency>>>();
        dependencies = new HashMap<String, List<Dependency>>();
        reverseDependencies = new HashMap<String, List<Dependency>>();

        initializeJob = createJob();
        initializeJob.setPriority(Job.BUILD);
        initializeJob.schedule(JOB_START_DELAY);
        initializeJob.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent arg0) {
                callback.triggerTreeRefresh();
            }
        });
        
        extProjectJob = createExternalDepJob();
        extProjectJob.setPriority(Job.BUILD);
        extProjectJob.schedule(JOB_START_DELAY);
        extProjectJob.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent arg0) {
                callback.triggerTreeRefresh();
            }
        });
    }
    
	private Job createExternalDepJob() {
		return new Job(EXT_DEPS_JOB_NAME) {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				scriptFolder = rootFolder + "scripts";

				try {
			        NavajoDependencyPreferences pref = NavajoDependencyPreferences.getInstance();
					List<IProject> tipiProjects = pref.getTipiProjects();
					monitor.beginTask("Calculating external project dependencies", tipiProjects.size());

					addExternalProjectDependencies(monitor);

					if (monitor.isCanceled()) {
						return Status.CANCEL_STATUS;
					}

				} finally {
					monitor.done();
					extProjectJob = null;
				}
				
				return Status.OK_STATUS;

			}
		};
	}

    private Job createJob() {

        return new Job(INIT_JOB_NAME) {

            @Override
			protected IStatus run(IProgressMonitor monitor) {

				scriptFolder = rootFolder + "scripts";

				// We missed changes, so re-read all files
				String[] xmlExt = { "xml", };
				Collection<File> files = FileUtils.listFiles(new File(scriptFolder), xmlExt, true);

				String[] scalaExt = { "scala", };
				Collection<File> scalaFiles = FileUtils.listFiles(new File(scriptFolder), scalaExt, true);

				Collection<File> workflowFiles = FileUtils.listFiles(new File(rootFolder + "workflows"), xmlExt, true);

				try {

					monitor.beginTask("Calculating Navajo dependencies",
							files.size() + scalaFiles.size() + workflowFiles.size());

					for (File f : files) {
						monitor.subTask(" Calculating dependencies of: " + f.getAbsolutePath());
						try {
							addDependencies(TreeObject.getScriptFromFilename(f.getAbsolutePath()), f);
						} catch (Exception e) {
							// Something went wrong in this file, going to try
							// to continue
							logger.error("Exception in getting dependencies of {}: {}", f.getAbsolutePath(), e);
						}
						monitor.worked(1);

						if (monitor.isCanceled()) {
							return Status.CANCEL_STATUS;
						}
					}
					for (File f : scalaFiles) {
						addScalaDependencies(f);
						monitor.worked(1);
						
						if (monitor.isCanceled()) {
							return Status.CANCEL_STATUS;
						}
					}
					addWorkflowDependencies(scriptFolder, monitor);
					addArticleDependencies(scriptFolder, monitor);
					addTasksDependencies(scriptFolder, monitor);

					if (monitor.isCanceled()) {
						return Status.CANCEL_STATUS;
					}

					initialized = true;
				} finally {
					monitor.done();
					if (!initialized) {
						initializeJob = null;
					}
				}

				return Status.OK_STATUS;

			}
        };
    }

    @Override
    public void addDependencies(String script, File scriptFile) {
        super.addDependencies(script, scriptFile);
        List<Dependency> myDependencies = new ArrayList<Dependency>();
        if (script.startsWith("entity") && script.endsWith("entitymapping")) {
            File entityMapping = new File(rootFolder + File.separator + "scripts",script + ".xml");
            try {
                codeSearch.searchEntityMappingDependenciesInDir(entityMapping, myDependencies, scriptFolder);
            } catch (Exception e) {
                logger.error("Exception on getting entity depencencies for {}: {}", e);
                return;
            }
        }
        synchronized (externalLock) {
            updateDependencies(myDependencies);
        }

    }
    
    public void addScalaDependencies(File f) {
        List<Dependency> myDependencies = new ArrayList<>();
        codeSearch.addScalaDependencies(f, myDependencies, scriptFolder);
        synchronized (externalLock) {
            updateDependencies(myDependencies);
        }

    }

    private void addWorkflowDependencies(String scriptFolder, IProgressMonitor monitor) {
        logger.debug("Starting workflow dependencies");
        List<Dependency> myDependencies = new ArrayList<Dependency>();
        try {
            codeSearch.addWorkflowDependencies(scriptFolder, myDependencies, monitor);
        } catch (Exception e) {
            logger.error("Exception on getting workflow depencencies for {}: {}", e);
            return;
        }

        synchronized (externalLock) {
            for (Dependency dep : myDependencies) {

                try {
                    if (!dependencies.containsKey(dep.getScript())) {
                        dependencies.put(dep.getScript(), new ArrayList<Dependency>());
                    }

                    dependencies.get(dep.getScript()).add(dep);

                    if (!reverseDependencies.containsKey(dep.getDependee())) {
                        reverseDependencies.put(dep.getDependee(), new ArrayList<Dependency>());
                    }

                    reverseDependencies.get(dep.getDependee()).add(dep);
                } catch (Exception e) {
                    logger.error("Error in processing Workflow dependency {} to {}:  {}", dep.getScriptFile(), dep.getDependeeFile(), e);
                }
            }

        }

        logger.debug("Done workflow dependencies");

    }

    private void addTasksDependencies(String scriptFolder, IProgressMonitor monitor) {
        logger.debug("Starting tasks dependencies");
        List<Dependency> myDependencies = new ArrayList<Dependency>();
        try {
            codeSearch.addTasksDependencies(scriptFolder, myDependencies, monitor);
        } catch (Exception e) {
            logger.error("Exception on getting tasks depencencies for {}: {}", e);
            return;
        }

        synchronized (externalLock) {
            for (Dependency dep : myDependencies) {
                try {
                    if (!dependencies.containsKey(dep.getScript())) {
                        dependencies.put(dep.getScript(), new ArrayList<Dependency>());
                    }

                    dependencies.get(dep.getScript()).add(dep);

                    if (!reverseDependencies.containsKey(dep.getDependee())) {
                        reverseDependencies.put(dep.getDependee(), new ArrayList<Dependency>());
                    }

                    reverseDependencies.get(dep.getDependee()).add(dep);
                } catch (Exception e) {
                    logger.error("Error in processing tasks dependency {} to {}:  {}", dep.getScriptFile(), dep.getDependeeFile(), e);
                }
                ;
            }
        }
        logger.debug("Done tasks dependencies");

    }

    private void addArticleDependencies(String scriptFolder, IProgressMonitor monitor) {
        logger.debug("Starting article dependencies");
        List<Dependency> myDependencies = new ArrayList<Dependency>();
        try {
            codeSearch.addArticleDependencies(scriptFolder, myDependencies, monitor);
        } catch (Exception e) {
            logger.error("Exception on getting workflow depencencies for {}: {}", e);
            return;
        }
        synchronized (externalLock) {

            for (Dependency dep : myDependencies) {
                try {
                    if (!dependencies.containsKey(dep.getScript())) {
                        dependencies.put(dep.getScript(), new ArrayList<Dependency>());
                    }

                    dependencies.get(dep.getScript()).add(dep);

                    if (!reverseDependencies.containsKey(dep.getDependee())) {
                        reverseDependencies.put(dep.getDependee(), new ArrayList<Dependency>());
                    }

                    reverseDependencies.get(dep.getDependee()).add(dep);
                } catch (Exception e) {
                    logger.error("Error in processing Article dependency {} to {}:  {}", dep.getScriptFile(), dep.getDependeeFile(), e);
                }
            }

        }
        logger.debug("Done article dependencies");

    }

    private void addExternalProjectDependencies(IProgressMonitor monitor) {
    	
    	
        NavajoDependencyPreferences pref = NavajoDependencyPreferences.getInstance();
        List<IProject> projectsToSearch = pref.getTipiProjects();

        for (IProject project : projectsToSearch) {
            List<Dependency> myDependencies = new ArrayList<Dependency>();

            try {
                codeSearch.addProjectDependencies(project, myDependencies, scriptFolder, monitor);
            } catch (Exception e) {
                logger.error("Exception on getting project depencencies for {}", project, e);
            }

            synchronized (externalProjLock) {
                Map<String, List<Dependency>> projectDeps = externaldependencies.get(project.getName());
                if (projectDeps == null) {
                    projectDeps = new HashMap<String, List<Dependency>>();
                }

                Map<String, List<Dependency>> reverseProjectDeps = reverseExternaldependencies.get(project.getName());
                if (reverseProjectDeps == null) {
                    reverseProjectDeps = new HashMap<String, List<Dependency>>();
                }
                for (Dependency dep : myDependencies) {
                    try {
                        if (!projectDeps.containsKey(dep.getScript())) {
                            projectDeps.put(dep.getScript(), new ArrayList<Dependency>());
                        }
                        projectDeps.get(dep.getScript()).add(dep);

                        if (!reverseProjectDeps.containsKey(dep.getDependee())) {
                            reverseProjectDeps.put(dep.getDependee(), new ArrayList<Dependency>());
                        }

                        reverseProjectDeps.get(dep.getDependee()).add(dep);
                    } catch (Exception e) {
                        logger.error("Error in processing external project dependency {} to {}:  {}", dep.getScriptFile(), dep.getDependeeFile(), e);
                    }

                }

                reverseExternaldependencies.put(project.getName(), reverseProjectDeps);
                externaldependencies.put(project.getName(), projectDeps);

            }
            monitor.worked(1);
        }

    }

    public void refresh(String filename, IProject project, boolean recursive) {
        synchronized (externalLock) {
            if (NavajoDependencyPreferences.getInstance().getScriptsProject().equals(project)) {
                String scriptName = TreeObject.getScriptFromFilename(filename);
                removeScriptFromReverseValues(scriptName);
                dependencies.remove(scriptName);
                if (filename.contains("scripts")) {
                    addDependencies(scriptName, new File(filename));
                } else {
                    List<Dependency> myDependencies = new ArrayList<Dependency>();
                    File f = new File(filename);
                    if (filename.contains("workflows")) {
                        codeSearch.searchWorkflowFile(f, myDependencies, scriptFolder);
                    } else if (filename.contains("article")) {
                        codeSearch.searchArticleFile(f, myDependencies, scriptFolder);
                    } else if (filename.contains("tasks.xml")) {
                        codeSearch.searchTasksFile(f, myDependencies, scriptFolder);
                    }
                    updateDependencies(myDependencies);

                }

                // Update the dependencies of all scripts that point(ed) to me
                List<Dependency> reverseDeps = reverseDependencies.get(scriptName);

                if (reverseDeps != null && recursive) {
                    // The refresh action can trigger removing dependencies.
                    // Therefore make a copy of the list to prevent
                    // ConcurrentMod exceptions
                    List<Dependency> depsCopy = new ArrayList<Dependency>(reverseDeps);
                    for (Dependency dep : depsCopy) {
                        if (!dep.getScriptFile().equals(filename)) {
                            refresh(dep.getScriptFile(), project, false);
                        }
                    }
                }

            } else {
                externaldependencies.clear();
                reverseExternaldependencies.clear();
                
                if (extProjectJob == null) {
                	extProjectJob = createExternalDepJob();
                    extProjectJob.setPriority(Job.BUILD);
                    extProjectJob.schedule(JOB_START_DELAY);
                    
                }
                
                
                //addExternalProjectDependencies(new NullProgressMonitor());
            }
        }

    }

    private void updateDependencies(List<Dependency> myDependencies) {
        for (Dependency dep : myDependencies) {
            try {
                if (!dependencies.containsKey(dep.getScript())) {
                    dependencies.put(dep.getScript(), new ArrayList<Dependency>());
                }

                dependencies.get(dep.getScript()).add(dep);

                if (!reverseDependencies.containsKey(dep.getDependee())) {
                    reverseDependencies.put(dep.getDependee(), new ArrayList<Dependency>());
                }

                reverseDependencies.get(dep.getDependee()).add(dep);
            } catch (Exception e) {
                logger.error("Error in processing Entity dependency {} to {}:  {}", dep.getScriptFile(), dep.getDependeeFile(), e);
            }
        }
    }

    public void remove(String scriptFile, IProject project) {
        synchronized (externalLock) {
            String scriptName = TreeObject.getScriptFromFilename(scriptFile);
            removeScriptFromReverseValues(scriptName);
            updatedReverseToBroken(scriptName);
            dependencies.remove(scriptName);
        }
    }

    private void updatedReverseToBroken(String scriptName) {
        synchronized (externalLock) {
            List<Dependency> deps = reverseDependencies.get(scriptName);
            if (deps == null) {
                return;
            }

            for (Dependency dep : deps) {
                dep.setBroken(true);
            }
        }
    }

    private void removeScriptFromReverseValues(String scriptName) {
        synchronized (externalLock) {
            List<Dependency> deps = dependencies.get(scriptName);
            if (deps == null) {
                return;
            }

            for (Dependency dep : deps) {
                List<Dependency> reverseDeps = reverseDependencies.get(dep.getDependee());
                if (reverseDeps == null)
                    continue; // nothing to do...

                List<Dependency> toRemove = new ArrayList<Dependency>();
                for (Dependency reverseDep : reverseDeps) {
                    if (dep == reverseDep) {
                        toRemove.add(reverseDep);
                    }
                }
                reverseDeps.removeAll(toRemove);
                reverseDependencies.put(dep.getDependee(), reverseDeps);
            }
        }
    }

    @Override
    public List<Dependency> getDependencies(String scriptName) {
        synchronized (externalLock) {
            List<Dependency> superDeps = super.getDependencies(scriptName);
            if (superDeps == null) {
                superDeps = new ArrayList<Dependency>();
            }

            List<Dependency> deps = new ArrayList<Dependency>(superDeps);

            for (Map<String, List<Dependency>> externalDeps : externaldependencies.values()) {
                List<Dependency> projectDeps = externalDeps.get(scriptName);
                if (projectDeps != null) {
                    deps.addAll(projectDeps);
                }
            }
            return deps;
        }
    }

    @Override
    public List<Dependency> getReverseDependencies(String scriptPath) {
        List<Dependency> reverseDeps = null;
        synchronized (externalLock) {
            List<Dependency> superDeps = super.getReverseDependencies(scriptPath);

            if (superDeps == null) {
                superDeps = new ArrayList<Dependency>();
            }

            reverseDeps = new ArrayList<Dependency>(superDeps);

            String script = scriptPath;
            if (scriptPath.indexOf('_') > 0) {
                // Remove tenant-specific part
                script = scriptPath.substring(0, scriptPath.indexOf('_'));
            }
            for (Map<String, List<Dependency>> externalDeps : reverseExternaldependencies.values()) {
                List<Dependency> projectDeps = externalDeps.get(script);
                if (projectDeps != null) {
                    reverseDeps.addAll(projectDeps);
                }

            }
        }
        return reverseDeps;

    }

    // Includes some caching since this is used to draw the 'broken file' icons
    // by the Navajo Decorator
    public boolean containsBrokenDependencies(String scriptPath) {
        if (!initialized) {
            return false;
        }
        List<Dependency> deps = getDependencies(scriptPath);
        if (deps == null) {
            return false;
        }

        for (Dependency dep : deps) {
            if (dep.isBroken()) {
                return true;
            }
        }
        return false;
    }

    public synchronized void cancelJob() {
        if (initializeJob != null) {
            initializeJob.cancel();
        }
        if (extProjectJob != null) {
        	extProjectJob.cancel();
        }
    }
    
    public static void main(String[] args) {
        EclipseDependencyAnalyzer instance = new EclipseDependencyAnalyzer(false);
        File f= new File ("/home/chris/git/sportlink/scripts/scala/example/ProcessQueryMatchTeamMembers.scala");
        instance.addScalaDependencies(f);
        
        
    }
}
