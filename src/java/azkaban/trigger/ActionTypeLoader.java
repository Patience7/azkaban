package azkaban.trigger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import azkaban.utils.Props;
import azkaban.utils.Utils;

public class ActionTypeLoader {
	
	private static Logger logger = Logger.getLogger(ActionTypeLoader.class);
	
	public static final String DEFAULT_TRIGGER_ACTION_PLUGIN_DIR = "plugins/triggeractions";
	private static final String ACTIONTYPECONFFILE = "plugin.properties"; // need jars.to.include property, will be loaded with user property
	private static final String COMMONCONFFILE = "common.properties";	// common properties for multiple plugins

	protected static Map<String, Class<? extends TriggerAction>> actionToClass = new HashMap<String, Class<? extends TriggerAction>>();
	
	public void init(Props props) throws TriggerException {
		// load built-in actions
		

		loadDefaultActions();
		
		loadPluginActions(props);

	}
	
	private void loadPluginActions(Props props) throws TriggerException {
		String checkerDir = props.getString("azkaban.trigger.action.plugin.dir", DEFAULT_TRIGGER_ACTION_PLUGIN_DIR);
		File pluginDir = new File(checkerDir);
		if(!pluginDir.exists() || !pluginDir.isDirectory() || !pluginDir.canRead()) {
			logger.info("No trigger action plugins to load.");
			return;
		}
		
		logger.info("Loading plugin trigger actions from " + pluginDir);
		ClassLoader parentCl = this.getClass().getClassLoader();
		
		Props globalActionConf = null;
		File confFile = Utils.findFilefromDir(pluginDir, COMMONCONFFILE);
		try {
			if(confFile != null) {
				globalActionConf = new Props(null, confFile);
			} else {
				globalActionConf = new Props();
			}
		} catch (IOException e) {
			throw new TriggerException("Failed to get global properties." + e);
		}
		
		for(File dir : pluginDir.listFiles()) {
			if(dir.isDirectory() && dir.canRead()) {
				try {
					loadPluginTypes(globalActionConf, pluginDir, parentCl);
				} catch (Exception e) {
					logger.info("Plugin actions failed to load. " + e.getCause());
					throw new TriggerException("Failed to load all trigger actions!", e);
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void loadPluginTypes(Props globalConf, File dir, ClassLoader parentCl) throws TriggerException {
		Props actionConf = null;
		File confFile = Utils.findFilefromDir(dir, ACTIONTYPECONFFILE);
		if(confFile == null) {
			logger.info("No action type found in " + dir.getAbsolutePath());
			return;
		}
		try {
			actionConf = new Props(globalConf, confFile);
		} catch (IOException e) {
			throw new TriggerException("Failed to load config for the action type", e);
		}
		
		String actionName = dir.getName();
		String actionClass = actionConf.getString("action.class");
		
		List<URL> resources = new ArrayList<URL>();		
		for(File f : dir.listFiles()) {
			try {
				if(f.getName().endsWith(".jar")) {
					resources.add(f.toURI().toURL());
					logger.info("adding to classpath " + f.toURI().toURL());
				}
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				throw new TriggerException(e);
			}
		}
		
		// each job type can have a different class loader
		ClassLoader actionCl = new URLClassLoader(resources.toArray(new URL[resources.size()]), parentCl);
		
		Class<? extends TriggerAction> clazz = null;
		try {
			clazz = (Class<? extends TriggerAction>)actionCl.loadClass(actionClass);
			actionToClass.put(actionName, clazz);
		}
		catch (ClassNotFoundException e) {
			throw new TriggerException(e);
		}
		
		if(actionConf.getBoolean("need.init")) {
			try {
				Utils.invokeStaticMethod(actionCl, actionClass, "init", actionConf);
			} catch (Exception e) {
				e.printStackTrace();
				logger.error("Failed to init the action type " + actionName);
				throw new TriggerException(e);
			}
		}
		
		logger.info("Loaded action type " + actionName + " " + actionClass);
	}
	
	private void loadDefaultActions() {
		actionToClass.put(ExecuteFlowAction.type, ExecuteFlowAction.class);		
	}
	
	public TriggerAction createActionFromJson(String type, Object obj) throws SecurityException, IllegalArgumentException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
		TriggerAction action = null;
		Class<? extends TriggerAction> actionClass = actionToClass.get(type);		
		action = (TriggerAction) Utils.invokeStaticMethod(actionClass.getClassLoader(), actionClass.getName(), "createFromJson", obj);
		
		return action;
	}
	
	public TriggerAction createAction(String type, Object ... args) {
		TriggerAction action = null;
		Class<? extends TriggerAction> actionClass = actionToClass.get(type);		
		action = (TriggerAction) Utils.callConstructor(actionClass, args);
		
		return action;
	}
}
