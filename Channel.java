import java.util.Vector;


public class Channel {
	public Vector<String> usersInChannel;
	public boolean serverOnChannel;
	public String channelName;
	
	public Channel(User user, String channelName){
		this.usersInChannel = new Vector<String>(10, 10);
		serverOnChannel = true;
		this.usersInChannel.add(user.username);
		this.channelName = channelName;
		
	}
	
	//overloaded when we just want to subscribe the sever to the channel 
	public Channel(String channelName)
	{
		this.usersInChannel = new Vector<String>(10,10);
		serverOnChannel = true;
		this.channelName = channelName;
	}
	
	public Vector<String> getChannel()
	{
		return usersInChannel;
	}
	
	public int addUserToChannel(User user){
		if(usersInChannel.contains(user.username))
			return 0;
		this.usersInChannel.add(user.username);
		return 1;
	}
	public int removeUserFromChannel(User user){
		this.usersInChannel.remove(user.username);
		if(this.usersInChannel.isEmpty())
			return 0;
	
		return 1;
	}
}