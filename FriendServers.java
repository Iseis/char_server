import java.net.InetAddress;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;


public class FriendServers {
	public InetAddress userAddress;
	public int userPort;
	public Vector<String> channels;
	private Timer sentTimer;
	private TimerTask task;
	public int timeSinceLastSent;
	
	public FriendServers(InetAddress ua, int up)
	{
		this.userAddress = ua;
		this.userPort = up;
		this.channels = new Vector<String>(5,5);
		timeSinceLastSent = 0;
		sentTimer = new Timer();
		
		task = new TimerTask() {
			
			@Override
			public void run() {
				timeSinceLastSent++;				
			}
		};
		
		sentTimer.scheduleAtFixedRate(task, 0, 1000);
	}
	
}
