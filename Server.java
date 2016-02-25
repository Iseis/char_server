import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;


public class Server {
	
	private static final int SAY = 0;
	private static final int LIST = 1;
	private static final int WHO = 2;
	private static final int ERROR = 3;
	private static final int S2SJOIN = 8;
	private static final int S2SLEAVE = 9;
	private static final int S2SSAY = 10;
	
	private int port = -1;
	private InetAddress ipAddress;
	private SecureRandom rand;
	private Vector<Long> uniqueIdentifiers;
	private Vector<FriendServers> friends;
	private Vector<Channel> channels;
	private Vector<User> loggedOnUsers;
	private Vector<DatagramPacket> requestQ;
	private ProcessorThread pt;
	private DatagramSocket serverSocket;
	private Timer listenForJoinTimer;
	private Timer sendJoinTimer;
	private TimerTask sendJoinTask;
	private TimerTask listenJoinTask;
	
	
	int ntohl(int input)
{
  return
         (input >>> 24) |
         (input >> 8) & 0x0000ff00 |
         (input << 8) & 0x00ff0000 |
         (input << 24);
   
}
	public static void main(String[] args){
		// Check that arguments are received
		if(args.length < 2){
			System.out.println("Usage: java Server <host address> <port>");
			System.exit(0);
		}
		//  Make a new server instance
		Server s = new Server(Integer.parseInt(args[1]));
		// initialize socket pointer
		s.serverSocket = null;
		// create new Datagram socket
		try {
			s.serverSocket = new DatagramSocket(s.port, InetAddress.getByName(args[0]));
		} catch (SocketException e1) {
			e1.printStackTrace();
			System.out.println("Server Socket Exception:  Failed to create server socket. Exiting.");
			System.exit(0);
		} catch (UnknownHostException e) {
			e.printStackTrace();
			System.out.println("Server UNKNOWN HOST EXCEPTION:  Invalid hostname.  Exiting.");
			System.exit(0);
		}
		//go through rest of args and add the friend servers to this servers list
		try
		{
			int index = 0;
			for(int i = 2; i < args.length; i += 2)
			{
				FriendServers temp = new FriendServers(InetAddress.getByName(args[i]), Integer.parseInt(args[i+1]));
				s.friends.add(temp);
				System.out.println("Friend server added. IP: " + s.friends.elementAt(index).userAddress + " Port: " 
						+ s.friends.elementAt(index).userPort);
				index++;
			}
		}catch (Exception a){System.out.println("No friend servers just me.");}
		// start the processor thread
		s.pt.start();
		// receive loop, receives packet, inserts into thread-safe vector, repeats
		while(true){
			// allocate new packet
			byte[] receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			// blocking receive
			try {
				s.serverSocket.receive(receivePacket);
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("Server IOEXCEPTION: Failed to receive packet. Continuing.");
				continue;
			}
			// insert into vector
			s.requestQ.add(receivePacket);
		}
	}
	public Server(int port){
		this.port = port;
		this.channels = new Vector<Channel>(10,10);
		this.loggedOnUsers = new Vector<User>(10,10);
		this.friends = new Vector<FriendServers>(10,10);
		this.pt = new ProcessorThread();
		this.uniqueIdentifiers = new Vector<Long>(10); //holds current identifiers
		rand = new SecureRandom(); // for unique identifiers
		requestQ = new Vector<DatagramPacket>(10,10);
		listenForJoinTimer = new Timer();
		sendJoinTimer = new Timer();
		
		//task for sending out a new join every minute
		sendJoinTask = new TimerTask() {
			public void run() 
			{
				for(FriendServers fr: friends)
				{
					if(fr != null)
					{
						for(String channel: fr.channels)
						{
							if(channel != null)
								sendS2SJoin(channel);
						}
					}
				}
			}
		};
		
		//Task for checking if we have a dead server
		listenJoinTask = new TimerTask() {
			public void run() 
			{
				for(FriendServers fr: friends)
				{
					if(fr.timeSinceLastSent >= 60 && fr.channels.isEmpty())
					{
						System.out.println("Friend Server: " + fr.userAddress + ":" + fr.userPort + " Has timed out: " + fr.timeSinceLastSent 
								+ " secs. Since last joined sent.");
						fr.channels.clear();
					}
				}
				
			}
		};
		
		listenForJoinTimer.scheduleAtFixedRate(listenJoinTask, 0, 60*2*1000);
		sendJoinTimer.scheduleAtFixedRate(sendJoinTask, 0, 60*1000);
		
	}

	/*
	 *  handler:  processes packets in the requestQ
	 *  @param recPack: DatagramPacket pulled from requestQ vector
	 */
	public void handler(DatagramPacket recPack){
		InetAddress uAdd = recPack.getAddress(); // get return address
		int uPort = recPack.getPort();  // get return port
		byte[] rec = new byte[recPack.getLength()];
		// get the byte array from the packet
		rec = recPack.getData();
		int dataLength = recPack.getLength();
		// if no data, return
		if(dataLength <= 0){
			sendError(uAdd, uPort, "Received packet with no data.");
			System.out.println("Server: Received empty packet.");
			return;
		}
		// make a new ByteBuffer to easily manage bytes
		ByteBuffer b = ByteBuffer.allocate(rec.length);
		// puts the byte array into the buffer

		b.put(rec, 0, rec.length);
		// set position to 0, should already be there
		b.position(0);
		// get the integer value of first 32 bits or 4 bytes
		

		int request = b.getInt();

		if(request >9)
			request = ntohl(request);


		// sets position of buffer to after the int
		b.position(4);
		if(request == 0){  // if login request   ///////////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 36){
				sendError(uAdd, uPort, "Received "+dataLength+" byte login request, should be 36 bytes.");
				System.out.println("Server: Received Login request with length "+dataLength+" bytes.  Should be 36 bytes.");
				return;
			}
			byte[] userB = new byte[32];
			// get the next 32 bytes for the username
			b.get(userB, 0, 32);
			if(userB.length <= 0){
				sendError(uAdd, uPort, "Received Login Request with no login name.");
				System.out.println("Server: Received Login request with no login name.");
				return;
			}
			// make a string from the byte[]
			String user = new String(userB);
	
			if(user.indexOf('\0') != -1){
				user = user.substring(0, user.indexOf('\0'));
	
			}
			if(user.length() > 31){
				user = user.substring(0, 31);
			// checks the length of the string, kind of redundant since the length is checked a few lines above, but just in case...
			}else if(user.length() <= 0){
				sendError(uAdd, uPort, "Recieved Login Request with no login name.");
				System.out.println("Server: Received Login request with no login name.");
				return;
			}
	
			// makes a new user object
			User u = new User(uAdd, uPort, user);
			User tmp = null;
			// if user is already logged on, removes
			for(User us: loggedOnUsers){
				if(us.username.equals(user) && us.userPort == uPort && us.userAddress.equals(uAdd)){
					tmp = us;
					break;  // user found
				}
			}
			if(tmp != null){
				loggedOnUsers.remove(tmp);
				System.out.println("Server: User "+ user +" - Duplicate user removed.");
			}
			// adds user to user vector
			loggedOnUsers.add(u);
			System.out.println("Server: "+user+ " logged in.");
			return;
		}else if(request == 1){ // logout request   //////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 4){
				// doesn't send error message to client since client only logs off when exiting.
				System.out.println("Server: Received Logout request with length "+dataLength+" bytes.  Should be 4 bytes.");
				return;
			}
			User tmp = null;
			// find the user
			for(User u: loggedOnUsers){
				if(u.userPort == uPort && u.userAddress.equals(uAdd)){
					tmp = u;
					break; // user found
				}
			}
			// if user not logged in
			if(tmp == null){
				System.out.println("Server: Couldn't logoff user, user not found.");
				return;
			}else{  // remove user from channels
				for(String channelName: tmp.channels){ // for each channel in user's channel list
					// find the channel in the server's channel list
					for(Channel c: this.channels){
						if(c.channelName.equals(channelName)){ // if found
							int channelEmpty = c.removeUserFromChannel(tmp);  // remove the user from the channel, returns 0 if channel is now empty
							// if channel is now empty, delete the channel
							// if no friends are listening
							boolean isListening = false;
							for(FriendServers f: this.friends)
							{
								if(f.channels.contains(channelName))
								{
									isListening = true;
									break;
								}
							}
							if(channelEmpty == 0  && !isListening){
								this.channels.remove(c);  
								System.out.println("Server: Deleting channel "+c.channelName);
							}
							break; // channel found and updated appropriately
						}
					}
					
				}
				// now remove user from logged in vector
				System.out.println("Server: "+tmp.username +" logged off.");
				this.loggedOnUsers.remove(tmp);
			}
			return;
			
		}else if(request == 2){ // join request //////////////////////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 36){
				sendError(uAdd, uPort, "Received "+dataLength+" byte join request, should be 36 bytes.");
				System.out.println("Server: Received join request with length "+dataLength+" bytes.  Should be 36 bytes.");
				return;
			}
			byte[] channelB = new byte[32];
			b.get(channelB, 0, 32);
			String channel = new String(channelB);
			if(channel.indexOf('\0') != -1){
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			int le = channel.length();
			if(le > 31)
				channel = channel.substring(0, 31);
			else if(le <= 0){
				sendError(uAdd, uPort, "No Channel name received");
				return;
			}
			//get user
			User us = null;
			for(User u: this.loggedOnUsers){
				if(u.userAddress.equals(uAdd) && u.userPort == uPort){
					us = u;
					break; // user found
				}
			}
			if(us == null){ // user not found
				sendError(uAdd, uPort, "User not found.  Restart to logon.");
				System.out.println("Server: join request from unknown user");
				return;
			}
			// get the channel
			Channel ch = null;
			for(Channel c: this.channels){
				if(c.channelName.equals(channel)){
					ch = c;
					break; // channel found
				}
			}
			if(ch == null){ // channel not found: creating new channel
				this.channels.add(new Channel(us, channel));
				System.out.println("Server: Channel "+channel+" created.");
			}else{
				if(ch.addUserToChannel(us) != 1){
					sendError(uAdd, uPort, "User already in channel."); // add user to existing channel
					return;
				}
				
			}
			System.out.println("Server: "+us.username+" joined Channel "+channel+".");
			// add channel name to user's list
			sendS2SJoin(channel);

			us.channels.add(channel);
			return;
			
		}else if(request == 3){  // leave request  //////////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 36){
				sendError(uAdd, uPort, "Received "+dataLength+" byte leave request, should be 36 bytes.");
				System.out.println("Server: Received Leave request with length "+dataLength+" bytes.  Should be 36 bytes." + port);
				return;
			}
			byte[] channelB = new byte[32];
			b.get(channelB, 0, 32);
			String channel = new String(channelB);
		
			if(channel.indexOf('\0') != -1){
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			int le = channel.length();
			if(le > 31)
				channel = channel.substring(0, 31);
			else if (le <= 0){
				sendError(uAdd, uPort, "No Channel name received in leave request.");
				return;
			}
			//get user
			User us = null;
			for(User u: this.loggedOnUsers){
				if(u.userAddress.equals(uAdd) && u.userPort == uPort){
					us = u;
					break; // user found
				}
			}
			if(us == null){ // user not found
				sendError(uAdd, uPort, "User not found.  Restart to logon.");
				System.out.println("Server: leave request from unknown user");
				return;
			}
			// get the channel
			Channel ch = null;
			
			for(Channel c: this.channels){
				if(c.channelName.equals(channel)){
					ch = c;
					break; // channel found
				}
			}
			if(ch == null){ // channel not found
				sendError(uAdd, uPort, "Channel "+channel+" does not exist.");
				System.out.println("Server: Received leave request for Channel "+channel+" that does not exist.");
				return;
			}else{
				int emptyChannel = ch.removeUserFromChannel(us); // remove user from existing channel
				System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
						+ uAdd + ":" + uPort + " recv Request leave " + us.username + " " + channel);
				if(emptyChannel == 0)
				{
					//check and see if friends are listing on the channel
					int numFriendsOnChannel = 0;
					for(FriendServers fr : this.friends)
					{
						if(fr.channels.contains(ch.channelName))
						{
							numFriendsOnChannel++;
						}
					}
					if(numFriendsOnChannel == 1)
					{
						S2Sleave(ch.channelName);
						this.channels.remove(ch);
					}
				}
			}
			// remove channel name to user's list
			us.channels.remove(channel);
			return;
		}else if(request == 4){ // say request  ////////////////////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 100){
				sendError(uAdd, uPort, "Received "+dataLength+" byte say request, should be 100 bytes.");
				System.out.println("Server: Received say request with length "+dataLength+" bytes.  Should be 100 bytes.");
				return;
			}
			byte[] channelB = new byte[32];
			b.get(channelB, 0, 32);
			String channel = new String(channelB);
			if(channel.indexOf('\0') != -1){
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			int le = channel.length();
			if(le > 31)
				channel = channel.substring(0, 31);
			else if(le <= 0){
				sendError(uAdd, uPort, "Unable to send message.");
				System.out.println("Server Error: Received say request with empty channel bytes.");
				return;
			}
			// get the channel
			Channel ch = null;
			for(Channel c: this.channels){
				if(c.channelName.equals(channel)){
					ch = c;
					break; // channel found
				}
			}
			if(ch == null){
				sendError(uAdd, uPort, "Channel not found.  Type  /join "+channel+"  to create and join that channel.");
				System.out.println("Server: say request received for non-existent channel.");
				return;
			}
			//get user
			User us = null;
			for(User u: this.loggedOnUsers){
				if(u.userAddress.equals(uAdd) && u.userPort == uPort){
					us = u;
					break; // user found
				}
			}
			if(us == null){ // user not found
				sendError(uAdd, uPort, "User not found.  Restart to logon.");
				System.out.println("Error: say request from unknown user");
				return;
			}
			b.position(36);
			byte[] textB = new byte[64];
			b.get(textB, 0, 64);
			if(textB.length <= 0){
				sendError(uAdd, uPort, "Server: Empty messages waste everyone's time. -.- ");
				System.out.println("Server: empty message received");
				return;
			}
			String temp = new String(textB);
			System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
					+ uAdd + ":" + uPort + " recv Request Say " + us.username + " " + channel + " "+ temp);
			messageChannel(textB, us.username, ch);
			S2Ssay(textB, us.username, ch, uAdd, uPort);
			return;
		}else if(request == 5){  // list request  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 4){
				sendError(uAdd, uPort, "Received "+dataLength+" byte list request, should be 4 bytes.");
				System.out.println("Server: Received List request with length "+dataLength+" bytes.  Should be 4 bytes.");
				return;
			}
			sendList(uAdd, uPort);
			return;
		}else if(request == 6){  // who request  //////////////////////////////////////////////////////////////////////////////////////////////////////
			if(dataLength != 36){
				sendError(uAdd, uPort, "Received "+dataLength+" byte who request, should be 36 bytes.");
				System.out.println("Server: Received who request with length "+dataLength+" bytes.  Should be 36 bytes.");
				return;
			}
			byte[] channelB = new byte[32];
			b.get(channelB, 0, 32);
			String channel = new String(channelB);
			if(channel.indexOf('\0') != -1){
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			int le = channel.length();
			if(le > 31)
				channel = channel.substring(0, 31);
			else if(le <= 0){
				sendError(uAdd, uPort, "Cannot process request");
				System.out.println("Server Error: Received who request with empty channel bytes.");
				return;
			}
			System.out.println("Server: who request for channel "+channel);
			// get the channel object
			Channel ch = null;
			for(Channel c: channels){
				if(c.channelName.equals(channel)){
					ch = c;
					break; // channel found
				}
			}
			if(ch == null){
				sendError(uAdd, uPort, "Channel not found");
				System.out.println("Server: say request received for non-existent channel.");
				return;
			}
			sendWho(uAdd, uPort, ch);
			return;
		}
		else if(request == 8) ////// S2S Join /////////////////////////////////////////////////
		{
			if(dataLength != 36)
			{
				sendError(uAdd, uPort, "Received "+dataLength+" bytes S2S join request, should be 36 bytes.");
				System.out.println("Server: Received join request with length "+dataLength+" bytes.  Should be 36 bytes.");
				return;
			}
			byte[] channelB = new byte[32];
			b.get(channelB, 0, 32);
			String channel = new String(channelB);
			if(channel.indexOf('\0') != -1){
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			int le = channel.length();
			if(le > 31)
				channel = channel.substring(0, 31);
			else if(le <= 0){
				sendError(uAdd, uPort, "No Channel name received");
				return;
			}
			
			//find friendserver who sent message.
			FriendServers fr = null; 
			for(FriendServers f: this.friends)
			{
				if(f.userAddress.equals(uAdd) && f.userPort == uPort)
				{
					fr = f;
					fr.timeSinceLastSent = 0;
					break;
				}
			}
			System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
					+ uAdd + ":" + uPort + " recv S2S Join " + channel);
			//let ourself know that the friend server who sent the join listening on channel
			if(!fr.channels.contains(channel))
			{
				fr.channels.add(channel);
			}
			// see if we are subscribed to the channel in question.
			Channel ch = null;
			for(Channel c: this.channels){
				if(c.channelName.equals(channel)){
					ch = c;
					break; // channel found
				}
			}
			if(ch == null){ // channel not found: creating new channel
				this.channels.add(new Channel(channel));
			}else{
				if(ch.serverOnChannel == true) //already on channel just return
				{ 
					return;
				}
				
			}
				
			sendS2SJoin(channel);
			return;
		}
		else if(request == 9) // S2S Leave/////////////////////////////
		{
			if(dataLength != 36){
				sendError(uAdd, uPort, "Received "+dataLength+" byte S2S leave request, should be 36 bytes.");
				System.out.println("Server: Received Leave request with length "+dataLength+" bytes.  Should be 36 bytes." + port);
				return;
			}
			//Get the channel
			byte[] channelB = new byte[32];
			b.get(channelB, 0, 32);
			String channel = new String(channelB);
		
			//get just the string no extra stuff
			if(channel.indexOf('\0') != -1){
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			int le = channel.length();
			if(le > 31)
				channel = channel.substring(0, 31);
			else if (le <= 0){
				sendError(uAdd, uPort, "No Channel name received in leave request.");
				return;
			}
			
			//find friend
			FriendServers fr = null;
			for(FriendServers t : this.friends)
			{
				if(t.userAddress.equals(uAdd) && t.userPort == uPort)
				{
					fr = t;
					break;
				}
			}
			if(fr == null)
			{
				sendError(uAdd, uPort, "Not a friend of this server...friend!");
				return;
			}
			if(fr.channels.contains(channel))
			{
				fr.channels.remove(channel);
				this.channels.remove(channel);
			}
			else
			{
				System.out.println("Friend not subscribed to that channel");
				return;
			}
			System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
					+ uAdd + ":" + uPort + " recv S2S leave " + channel);
			return;
		}
		else if(request == 10)// S2S Say ///////////////////////////////////////////////////
		{
			if(dataLength != 140){
				sendError(uAdd, uPort, "Received "+dataLength+" byte S2S say request, should be 140 bytes.");
				System.out.println("S2S say: Received say request with length "+dataLength+" bytes.  Should be 140 bytes." + port);
				return;
			}
			long identifir =  b.getLong(); //get identifier
			
			b.position(12); // set postion after reading in the long
			byte[] userName = new byte[32];
			b.get(userName, 0, 32);
			String usName = new String(userName);
			if(usName.indexOf('\0') != -1){
				usName = usName.substring(0, usName.indexOf('\0'));
			}
			b.position(44);
			byte[] channelName = new byte[32];
			b.get(channelName, 0, 32);
			String channel = new String(channelName);
			// if we find matching identifier send leave and return.
			for(long i : this.uniqueIdentifiers)
			{
				if(i == identifir)
				{
					System.out.println("Recieved duplicate S2S say sending leave");
					S2Sleave(channel);
					return;
				}
			}
			//if no loop then save identifier
			if(uniqueIdentifiers.size() == uniqueIdentifiers.capacity())
				uniqueIdentifiers.clear();
			
			uniqueIdentifiers.add(identifir);

			if(channel.indexOf('\0') != -1)
			{
				channel = channel.substring(0, channel.indexOf('\0'));
			}
			Channel chan = null;
			for(Channel temp : this.channels)
			{
				if(temp.channelName.equals(channel))
				{
					chan = temp;
					break;
				}
			}
			int friendListening = 0;
			for(FriendServers fr: this.friends)
			{
				if(fr.channels.contains(channel))
				{
					friendListening++;
				}
			}
			//if no body listning to the and friends listening is less than 1 
			int numUsersListening = 0;
			for(User user : this.loggedOnUsers)
			{
				if(user.channels.contains(channel))
				{
					numUsersListening++;
				}
			}
			
			if(numUsersListening == 0 && friendListening <= 1)
			{
				this.channels.remove(chan);
				S2Sleave(channel);
				return;
			}
			
				
			b.position(76);
			byte[] textB = new byte[64];
			b.get(textB, 0, 64);
			String temp = new String(textB);
			System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
					+ uAdd + ":" + uPort + " recv S2S Say " + usName + " " + chan.channelName + " " + temp);
			
			
			//now send say
			S2Ssay(textB, usName, chan, uAdd, uPort);
			
			if(!chan.usersInChannel.isEmpty())
			{
				messageChannel(textB, usName, chan);
			}
			return;
		}
		sendError(uAdd, uPort, "Request type not defined.");
		return;		
	}
	/*
	 * messageChannel: sends message from sender to all users logged into channel c
	 */
	public void messageChannel(byte[] message, String sender, Channel c){
		
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(132);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
		byteBuffer.putInt(0,SAY);  // adds the request bytes
		byteBuffer.position(4);
		byte[] channelNameBytes = new byte[32];
		channelNameBytes = c.channelName.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int i = 0; i< 32 - channelNameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byteBuffer.position(36);
		byte[] nameBytes = sender.getBytes();
		byteBuffer.put(nameBytes, 0, nameBytes.length);
		for(int i = 0; i<32 - nameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byteBuffer.position(68);
		String mes = new String(message);
		if(mes.indexOf('\0') != -1){
			mes = mes.substring(0, mes.indexOf('\0'));
		}
		if(mes.length() > 63)
			mes = mes.substring(0, 63);
			
		message = mes.getBytes();
		byteBuffer.put(message, 0, message.length);  // adds the message bytes
		for(int i = 0; i< 64 - message.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[132];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		
		for(String u: c.usersInChannel){
			//find associated user object
			User user = this.getUserByName(u);
			if(user != null){
				// create a say packet
				DatagramPacket sendPacket = new DatagramPacket(sendData, 132, user.userAddress, user.userPort);
				try {
					this.serverSocket.send(sendPacket);
				} catch (IOException e) {
					e.printStackTrace();
					System.out.println("Server: Error sending say packet.");
				}
			}
		}		
	}
	
	/*
	 * Sends leave message to all friend servers who are also subscribed to the channel
	 * @param Channel to be left
	 */
	public void S2Sleave(String channel)
	{
		// Make a new ByteBuffer for easy concatenation of byte array
		ByteBuffer byteBuffer = ByteBuffer.allocate(36);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		byteBuffer.putInt(0, S2SLEAVE);
		byteBuffer.position(4);
		
		//bytes for channel name
		byte[]channelNameBytes = new byte[32];
		channelNameBytes = channel.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);
		for(int i = 0; i < 32 - channelNameBytes.length; i++)
		{
			byteBuffer.put((byte)00);
		}
		
		//now get make send bytes and send
		byte[] sendBytes = new byte[36];
		byteBuffer.rewind();
		byteBuffer.get(sendBytes);
		
		//send to every friend subbed to the channel
		for(FriendServers u: this.friends){
			//find associated user object that is subbed to channel
			if(u != null && u.channels.contains(channel))
			{
					System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
							+ u.userAddress + ":" + u.userPort + " send S2S leave " + channel);
					DatagramPacket sendPacket = new DatagramPacket(sendBytes, 36, u.userAddress, u.userPort);
					try 
					{
						this.serverSocket.send(sendPacket);
					}
					catch (IOException e) 
					{
						e.printStackTrace();
						System.out.println("Server: Error sending say packet.");
					}
			}
		}
	}
	
	/*
	 * Sends message to all friendly channels
	 * @param user name
	 * @param channel to be broadcast on
	 */
	public void S2Ssay(byte[] message, String user, Channel channel, InetAddress senderAdd, int senderPort)
	{
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(140);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
		byteBuffer.putInt(0,S2SSAY);  // adds the request bytes
		byteBuffer.position(4);
		long random = this.rand.nextLong(); //get next long for uniq identifier
		byteBuffer.putLong(4, random);
		byteBuffer.position(12);
		byte[] nameBytes = user.getBytes();
		byteBuffer.put(nameBytes, 0, nameBytes.length);
		for(int i = 0; i<32 - nameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byteBuffer.position(44);
		byte[] channelNameBytes = new byte[32];
		channelNameBytes = channel.channelName.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int i = 0; i< 32 - channelNameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byteBuffer.position(76);
		String mes = new String(message);
		if(mes.indexOf('\0') != -1){
			mes = mes.substring(0, mes.indexOf('\0'));
		}
		if(mes.length() > 63)
			mes = mes.substring(0, 63);
			
		message = mes.getBytes();
		byteBuffer.put(message, 0, message.length);  // adds the message bytes
		for(int i = 0; i< 64 - message.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[140];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		
		
		for(FriendServers u: this.friends)
		{
			//find associated user object
			if(u != null && u.channels.contains(channel.channelName)){
				// create a say packet
				if(u.userPort != senderPort)
				{
					System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
							+ u.userAddress + ":" + u.userPort + " send S2S say " + channel.channelName);
					DatagramPacket sendPacket = new DatagramPacket(sendData, 140, u.userAddress, u.userPort);
					try {
						this.serverSocket.send(sendPacket);
					} catch (IOException e) {
						e.printStackTrace();
						System.out.println("Server: Error sending say packet.");
					}
				}
			}
		}
	}
	
	/*
	 * Helper method to find User object using the username
	 * @param user name
	 * @returns User
	 */
	public User getUserByName(String name){
		User u = null;
		for(User us: loggedOnUsers){
			if(name.equals(us.username)){
				u = us;
			}
		}
		return u;
	}
	public int min(int a, int b){
		return (a <= b)? a: b;
	}
	/*
	 * sendList: sends list of all channels to user
	 * @params address and port of client to send packet to
	 */
	public void sendList(InetAddress add, int port){
		int numChan = channels.size();
		int size = 8 + (32 * numChan);
		// Make a new ByteBuffer for easily handling of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(size);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
byteBuffer.putInt(0,LIST);  // adds the type bytes
		byteBuffer.putInt(4,numChan); // adds the number of channels bytes
		byte[][] channelNameBytes = new byte[numChan][32];
		System.out.println("Listing Channels:");
		for(int i = 0; i< numChan; i++){
			byteBuffer.position(8+(32*i));
			Channel c = channels.elementAt(i);
			System.out.println("  "+c.channelName);
			channelNameBytes[i] = c.channelName.getBytes();
			int m = min(channelNameBytes[i].length, 32);
			byteBuffer.put(channelNameBytes[i], 0, m);  // adds the channel name bytes
			for(int k = 0; k< 32 - m; k++){
				byteBuffer.put((byte)00);
			}
		}
		byte[] sendData = new byte[size];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		System.out.println("Server: Sending List.");
		// create a say packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, size, add, port);
		//  Send say request packet
		try {
			this.serverSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Server IOException: error sending say packet. Continuing");
		}
		return;
	}
	/*
	 * sendWho: sends list of users logged onto channel c
	 * @params add & port - address and port of recipient
	 * @param channel c is channel whose logged on users will be listed
	 */
	public void sendWho(InetAddress add, int port, Channel c){
		int numUsers = c.usersInChannel.size();
		int size = 40 + (32 * numUsers);
		// Make a new ByteBuffer
		ByteBuffer byteBuffer = ByteBuffer.allocate(size);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		byteBuffer.putInt(0,WHO);  // adds the type bytes
		byteBuffer.putInt(4,numUsers); // adds the number of channels bytes
		byteBuffer.position(8);
		byte[] channelNameBytes = new byte[32];
		channelNameBytes = c.channelName.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int k = 0; k< 32 - channelNameBytes.length; k++){
			byteBuffer.put((byte)00);
		}
		byte[][] userNameBytes = new byte[numUsers][32];
		for(int i = 0; i< numUsers; i++){
			byteBuffer.position(40+(i*32));
			String u = c.usersInChannel.elementAt(i);
			userNameBytes[i] = u.getBytes();
			byteBuffer.put(userNameBytes[i], 0, userNameBytes[i].length);
			for(int j = 0; j< 32 - userNameBytes[i].length; j++){
				byteBuffer.put((byte)00);
			}
		}
		byte[] sendData = new byte[size];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		// create a who packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, size, add, port);
		//  Send who packet
		try {
			this.serverSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Server: error sending who packet.");
		}
		return;
	}
	
	/*
	 * Send S2S join: Sends a join message to all friend servers
	 * @param Channel name of channel to join
	 */
	public void sendS2SJoin(String channel)
	{
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(36);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
		byteBuffer.putInt(0, S2SJOIN);  // adds the request bytes
		byteBuffer.position(4);
		byte[] channelNameBytes = new byte[32];
		if(channel.length() > 31){
			System.out.println("Channel names can be a maximum of 31 characters in length. Try again.");
			return;
		}
		channelNameBytes = channel.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int i = 0; i< 32 - channelNameBytes.length; i++)
		{
			byteBuffer.put((byte)00);
		}
		
		byte[] sendData = new byte[36];
		byteBuffer.rewind();
		byteBuffer.get(sendData);
		for(FriendServers u: this.friends){
			//find associated user object
			if(u != null){
				// create a say packet
				System.out.println(serverSocket.getLocalAddress() + ":" + this.port + " "
						+ u.userAddress + ":" + u.userPort + " send S2S Join " + channel);
				DatagramPacket sendPacket = new DatagramPacket(sendData, 36, u.userAddress, u.userPort);
				try {
					this.serverSocket.send(sendPacket);
				} catch (IOException e) {
					e.printStackTrace();
					System.out.println("Server: Error sending say packet.");
				}
			}
		}
		return;
	}
	
	/*
	 * Error: Sends error packet to client detailing error
	 * @param InetAddress of client
	 * @param int p is the port of the client
	 * @param message is the error message sent to client
	 */
	public void sendError(InetAddress a, int p, String message){
		// allocates byteBuffer for the size of the data
		ByteBuffer byteBuffer = ByteBuffer.allocate(68); // 4 for the message type identifier + 64 for the message
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		byteBuffer.putInt(0,ERROR);  // adds the request bytes
		byteBuffer.position(4);
		byte[] messageBytes = new byte[64];
		if(message.length() > 63)
			message = message.substring(0,63);
		messageBytes = message.getBytes();
		byteBuffer.put(messageBytes, 0, messageBytes.length);  // adds the user name bytes
		for(int i = 0; i< 64 - messageBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[68];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		// create an error packet
		DatagramPacket errorPacket = new DatagramPacket(sendData, 68, a, p);
		//  Send error packet
		try {
			serverSocket.send(errorPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Server IOException : Error sending error packet."); // irony
		}
		return;
	}
	/*
	 *  Inner processor thread class:  processes each entry into the vector requestQ, which is functioning as a request queue.
	 */
	public class ProcessorThread extends Thread{
		public void run(){
			// loop removes packet from requestQ and calls the handler function
			while(true){
				// if the Q is empty, sleeps and starts loop over
				if(requestQ == null || requestQ.isEmpty()){
					try {
						sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
						System.out.println("Server InterruptedException: Thread failed to sleep.  Continuing.");
					}
					continue;
				}
				//  get the first element from requestQ while removing it
				DatagramPacket recPack = requestQ.remove(0);
				//  call on handler function to process request packet
				handler(recPack);
			}
		}
	}
}