package azkaban.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import azkaban.utils.Props;


public class TriggerManager {
	private static Logger logger = Logger.getLogger(TriggerManager.class);
	
	private final long DEFAULT_TRIGGER_EXPIRE_TIME = 24*60*60*1000L;
	
	private List<Trigger> triggers;
	
	TriggerScannerThread scannerThread;
	
	public TriggerManager(Props props, TriggerLoader triggerLoader, CheckerTypeLoader checkerLoader, ActionTypeLoader actionLoader) {
		
		// load plugins
		try{
			checkerLoader.init(props);
			actionLoader.init(props);
		} catch(Exception e) {
			e.printStackTrace();
			logger.error(e.getMessage());
		}
		
		Condition.setCheckerLoader(checkerLoader);
		Trigger.setActionTypeLoader(actionLoader);
		
		try{
			// expect loader to return valid triggers
			triggers = triggerLoader.loadTriggers();
		}catch(Exception e) {
			e.printStackTrace();
			logger.error(e.getMessage());
		}
		
		scannerThread = new TriggerScannerThread("TriggerScannerThread");
		
		for(Trigger t : triggers) {
			scannerThread.addTrigger(t);
		}
		
		scannerThread.start();
	}
	
	public synchronized void insertTrigger(Trigger t) {
		triggers.add(t);
		scannerThread.addTrigger(t);
	}
	
	public synchronized void removeTrigger(Trigger t) {
		scannerThread.removeTrigger(t);
		triggers.remove(t);		
	}
	
	public List<Trigger> getTriggers() {
		return new ArrayList<Trigger>(triggers);
	}

	//trigger scanner thread
	public class TriggerScannerThread extends Thread {
		private final BlockingQueue<Trigger> triggers;
		private AtomicBoolean stillAlive = new AtomicBoolean(true);
		private String scannerName;
		private long lastCheckTime = -1;
		
		// Five minute minimum intervals
		private static final int TIMEOUT_MS = 300000;
		
		public TriggerScannerThread(String scannerName){
			triggers = new LinkedBlockingDeque<Trigger>();
			this.setName(scannerName);
		}
		
		public void shutdown() {
			logger.error("Shutting down trigger manager thread " + scannerName);
			stillAlive.set(false);
			this.interrupt();
		}
		
		public synchronized List<Trigger> getTriggers() {
			return new ArrayList<Trigger>(triggers);
		}
		
		public synchronized void addTrigger(Trigger t) {
			triggers.add(t);
		}
		
		public synchronized void removeTrigger(Trigger t) {
			triggers.remove(t);
		}
		
		public void run() {
			while(stillAlive.get()) {
				synchronized (this) {
					try{
						lastCheckTime = System.currentTimeMillis();
						
						try{
							checkAllTriggers();
						} catch(Exception e) {
							e.printStackTrace();
							logger.error(e.getMessage());
						} catch(Throwable t) {
							t.printStackTrace();
							logger.error(t.getMessage());
						}
						
						long timeRemaining = TIMEOUT_MS - (System.currentTimeMillis() - lastCheckTime);
						if(timeRemaining < 0) {
							logger.error("Trigger manager thread " + scannerName + " is too busy!");
						} else {
							wait(timeRemaining);
						}
					} catch(InterruptedException e) {
						logger.info("Interrupted. Probably to shut down.");
					}
					
				}
			}
		}
		
		private void checkAllTriggers() {
			for(Trigger t : triggers) {
				if(t.triggerConditionMet()) {
					onTriggerTrigger(t);
				} else if (t.expireConditionMet()) {
					onTriggerExpire(t);
				}
			}
			
		}
		
		private void onTriggerTrigger(Trigger t) {
			List<TriggerAction> actions = t.getTriggerActions();
			for(TriggerAction action : actions) {
				action.doAction();
			}
			if(t.isResetOnTrigger()) {
				t.resetTriggerConditions();
			} else {
				triggers.remove(t);
			}
		}
		
		private void onTriggerExpire(Trigger t) {
			if(t.isResetOnExpire()) {
				t.resetTriggerConditions();
			} else {
				triggers.remove(t);
			}
		}
	}
	
	public TriggerAction createTriggerAction() {
		return null;
	}

}
