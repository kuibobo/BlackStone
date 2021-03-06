package apollo.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import apollo.bll.AutoPosts;
import apollo.bll.Posts;
import apollo.bll.Users;
import apollo.data.model.AutoPost;
import apollo.data.model.Post;
import apollo.data.model.User;
import apollo.util.DataSet;
import apollo.util.DateTime;

public class AutoPostService extends Service {
	
	class AutoPostTimerTask extends TimerTask {

		public AutoPost post = null;
		private int curIdx = 0;
		
		@Override
		public void run() {		
			if (post.accounts.size() == 0)
				return;
			
			if (curIdx >= post.accounts.size())
				curIdx = 0;
			
			try {
				createPost(post, curIdx);
			} catch (Exception ex) {
				Log.e(getClass().getName(), "createPost exception:" + post.thread.getSubject());
			}
			curIdx++;
		}
	}
	
	class AutoPostThread extends Thread {
		
		AutoPost post = null;
		int curIdx = 0;
		boolean exit = false;
		
		@Override
		public void run() {
			int sleep_time = 10000;
			int cur_replys = 0;
			int diff = 0;
			
			if (post.accounts.size() == 0)
				exit = true;

			while(exit == false) {
				// 当前回复数不在回复区间内的时候
				
				diff = post.floorNum - cur_replys;
				if (diff < 0) {
					this.stop();
				} else if (diff > 0) {
					if (diff > 2000)
						sleep_time = 20000;
					else if (diff > 500)
						sleep_time = 10000;
					else if (diff > 200)
						sleep_time = 2800;
					else if (diff < post.accounts.size())
						sleep_time = -1;
					else
						sleep_time = 1100;
					
					if (sleep_time != -1) {
						try {sleep(sleep_time);} catch (InterruptedException e) {}
					} else {
						if (post.accounts.size() == 0)
							return;
						
						if (curIdx >= post.accounts.size())
							curIdx = 0;
						
						createPost(post, curIdx);
						curIdx++;
					}
					
				} 			
			}
		}
	}
	
	private HashMap<String, DateTime> mUserPostTimeMap;
	
	private void createPost(AutoPost ap, int userIdx) {
		Post p = null;
		User u = null;
		DateTime prePostTime = null;
		DateTime curPostTime = null;
		long postDiff = 0;
		
		p = new Post();
		p.setThreadId(ap.thread.getThreadId());
		p.setSection(ap.thread.getSection());
		p.setBody(ap.postBody);
		p.setSubject(ap.thread.getSubject());
		
		u = ap.accounts.get(userIdx);
		
		// 一个id在45秒内只能回复一次
		curPostTime = DateTime.now();
		prePostTime = mUserPostTimeMap.get(u.getName());
		if (prePostTime == null) {
			postDiff = 46000;
		} else {
			postDiff = DateTime.diff(curPostTime, prePostTime);
		}
	
		if (postDiff > 45000) {
			Posts.add(p, u);
			mUserPostTimeMap.put(u.getName(), curPostTime);
			Log.i("createPost", DateTime.now().toString() + "#" + p.getSubject() + "#" + u.getName());	
		}
	}
	
	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		mUserPostTimeMap = new HashMap<String, DateTime>();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		TimerTask daemon_task = null;
		Timer daemon_timer = null;
		final List<AutoPostThread> autopost_threads = new ArrayList<AutoPostThread>();
		final List<Timer> autopost_timers = new ArrayList<Timer>();
		
		daemon_task = new TimerTask(){
			@Override
			public void run() {
				DataSet<AutoPost> datas = null;
				User user = null;				
				
				// 清理过时的任务
				for(AutoPostThread apt:autopost_threads) {
					apt.exit = true;
				}
				autopost_threads.clear();
				
				for(Timer t:autopost_timers) {
					t.cancel();
				}
				autopost_timers.clear();
				
				// 重新处理新的任务
				datas = AutoPosts.getAutoPosts(1, Integer.MAX_VALUE);
				Log.i("daemon_task", "run:" + datas.getObjects().size());
				for(AutoPost ap:datas.getObjects()) {
					
					if (ap.accounts == null) {
						// ap.accounts为空既是全选所有用户
						DataSet<User> user_datas = Users.getUsers(1, Integer.MAX_VALUE);
						ap.accounts = new ArrayList<User>();
						ap.accounts.addAll(user_datas.getObjects());
					} else {
						// 处理选中的特定用户
						for(int i=0; i<ap.accounts.size(); i++) {
							user = ap.accounts.get(i);
							user = Users.getUserByName(user.getName());
							ap.accounts.set(i, user);
						}
					}

					if (ap.floorEnable) {
						AutoPostThread thread = null;
						thread = new AutoPostThread();
						thread.post = ap;
						thread.start();
						
						autopost_threads.add(thread);
					} else {
						AutoPostTimerTask autopost_task = null;
						Timer autopost_timer = null;
						long period = 0;
						
						autopost_task = new AutoPostTimerTask();
						autopost_task.post = ap;
						autopost_timer = new Timer(true);
						
						period = 45000 / ap.accounts.size();
						autopost_timer.scheduleAtFixedRate(autopost_task, 100, period);
						
						autopost_timers.add(autopost_timer);
					}
				}
			
			}
		};
		daemon_timer = new Timer(true);
		daemon_timer.scheduleAtFixedRate(daemon_task, 100, 60000);
	    		
		return super.onStartCommand(intent, flags, startId);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}
}
