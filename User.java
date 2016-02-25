import java.net.InetAddress;
import java.util.Vector;


public class User {
	public InetAddress userAddress;
	public int userPort;
	public Vector<String> channels;
	public String username = "";
	
	public User(InetAddress userAddress, int userPort, String username){
		this.userAddress = userAddress;
		this.userPort = userPort;
		this.channels = new Vector<String>(5,5);
		this.username = username;
	}
	
}