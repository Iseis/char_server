import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Vector;


public class Client {
	/*
	 *  Static Request variables for Client
	 */
	private static final int LOGIN = 0;
	private static final int LOGOUT = 1;
	private static final int JOIN = 2;
	private static final int LEAVE = 3;
	private static final int SAY = 4;
	private static final int LIST = 5;
	private static final int WHO = 6;
 
	/*
	 *  Global variables for Client Class
	 */
	private int serverPort = -1;
	private String username = "";
	private Vector<String> channels;
	private String activeChannel = "Common";
	private BufferedReader in;
	public DatagramSocket clientSocket;
	private InetAddress serverIPAddress;
	private ListenerThread listener;
	public String input = "";
	public boolean shouldStop = false;


	/********************************************
	*    MAIN
	********************************************/
	public static void main(String[] args) throws Exception{
		// Check for proper number of arguments
		if(args.length != 3){
			System.out.println("Usage: java Client <Server Host Address> <Server Listening Port> <Username>");
			System.exit(0);
		}
		// Check for acceptable user name length
		if(args[2].length() > 31){
			System.out.println("Error: username must be 32 characters or less");
			System.exit(0);
		}
		// Construct a new client object
		Client client = new Client(args[0], Integer.parseInt(args[1]), args[2]);
		// new input reader
		client.in = new BufferedReader(new InputStreamReader(System.in));
		// make a socket for sending and receiving
		client.clientSocket = new DatagramSocket();
		client.listener.start();
		// login to the server
		client.login();
		// join the common channel
		client.joinChannel("Common");
		while(true){
			client.prompt();
			char c = (char) client.in.read();
			while(c != '\n'){
				client.input += c;				
				c = (char)client.in.read();
			}
			client.process(client.input);
			client.input = "";
		}
	}


	/*
	 * Login
	 */
	public void login(){
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(36);
byteBuffer.order(ByteOrder.LITTLE_ENDIAN);				
byteBuffer.putInt(0,LOGIN);  // adds the request bytes
		byteBuffer.position(4);
		byte[] usernameBytes = new byte[32]; 
		usernameBytes = this.username.getBytes();
		byteBuffer.put(usernameBytes, 0, usernameBytes.length);  // adds the user name bytes
		for(int i = 0; i< 32 - usernameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[36];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		// create a login request packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, 36, this.serverIPAddress, this.serverPort);
		//  Send login request packet
		try {
			this.clientSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error sending login request.  Exiting.....");
			System.exit(0);
		}
		return;
	}
	/*
	 * Logout
	 */
	public void logout(){
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(4);
	byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
	byteBuffer.putInt(0,LOGOUT);  // adds the request bytes
		byte[] sendData = new byte[4];
		byteBuffer.get(sendData); // gets the byte[] from the buffer and puts it in sendData
		
		// create a logout request packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, 4, this.serverIPAddress, this.serverPort);
		//  Send logout request packet
		try {
			this.clientSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return;
	}
	/*
	 * Client Constructor
	 */
	public Client(String serverHostName, int serverPort, String username) throws SocketException{
		this.serverPort = serverPort;
		this.username = username;
		this.channels = new Vector<String>(10,10);
		this.listener = new ListenerThread();
		try {
			this.serverIPAddress = InetAddress.getByName(serverHostName);
		} catch (UnknownHostException e) {
			e.printStackTrace();
			System.out.println("Error getting IPAddress.  Exiting.");
			System.exit(0);
		}
		return;
	}

	/*
	 * join:  sends join message to server.  adds channel to users channel vector.
	 */
	public void joinChannel(String channel){
	// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(36);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
		byteBuffer.putInt(0,JOIN);  // adds the request bytes
		byteBuffer.position(4);
		byte[] channelNameBytes = new byte[32];
		if(channel.length() > 31){
			System.out.println("Channel names can be a maximum of 31 characters in length. Try again.");
			return;
		}
		channelNameBytes = channel.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int i = 0; i< 32 - channelNameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[36];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		// create a join request packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, 36, this.serverIPAddress, this.serverPort);
		//  Send join request packet
		try {
			this.clientSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error sending join request.");
		}
		// Add channel to channels list
		this.channels.add(channel);
		this.activeChannel = channel;
	}
	/*
	 * prompt:  prints the prompt in the terminal
	 */
	public void prompt(){
		System.out.print("> ");
		System.out.print(this.input);
		if(this.input.length() > 0){
			System.out.print(this.input);
		}
	}
	
	/*
	 * switchChannels:  Switches the active channel of the user
	 */
	public void switchChannels(String channel){
		if(channels.contains(channel)){
			this.activeChannel = channel;
		}else{
			System.out.println("You are not currently subscribed to that channel.\nTo switch to "+channel+", type /join "+ channel);
		}
	}
	
	/*
	 *  who:  sends who request to server.  Prints subscribers of channel argument.
	 */
	public void who(String channel){
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(36);
byteBuffer.order(ByteOrder.LITTLE_ENDIAN);			
	byteBuffer.putInt(0,WHO);  // adds the request bytes
		byteBuffer.position(4);
		byte[] channelNameBytes = new byte[32];
		if(channel.length() > 31){
			System.out.println("Channel names can be a maximum of 31 characters in length. Try again.");
			return;
		}
		channelNameBytes = channel.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int i = 0; i< 32 - channelNameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[36];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		// create a who request packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, 36, this.serverIPAddress, this.serverPort);
		//  Send who request packet
		try {
			this.clientSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error sending who request.");
		}
	}
	
	/*
	 * listChannels:  sends list request to server.  Prints a list of all the channels currently on the server 
	 */
	public void listChannels(){
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(4);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
byteBuffer.putInt(0,LIST);  // adds the request bytes
		byte[] sendData = new byte[4];
		byteBuffer.get(sendData); // gets the byte[] from the buffer and puts it in sendData
		// create a list request packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, 4, this.serverIPAddress, this.serverPort);
		//  Send list request packet
		try {
			this.clientSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error sending list request.");
		}
		return;
	}
	
	/*
	 * leave channel:  sends leave request to server.  Removes channel from users channel vector.
	 */
	public void leaveChannel(String channel){
		if(channel.length() > 31){
			System.out.println("Channel names can be a maximum of 31 characters in length. Try again.");
			return;
		}
		if(channels.contains(channel)){
			// Make a new ByteBuffer for easily concatenation of byte arrays
			
			ByteBuffer byteBuffer = ByteBuffer.allocate(36);

byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
			byteBuffer.putInt(0,LEAVE);  // adds the request bytes


			byteBuffer.position(4);
			byte[] channelNameBytes = new byte[32];
			channelNameBytes = channel.getBytes();
			byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
			for(int i = 0; i< 32 - channelNameBytes.length; i++){
				byteBuffer.put((byte)00);
			}
			byte[] sendData = new byte[36];
			byteBuffer.rewind();
			byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
			// create a leave request packet
			DatagramPacket sendPacket = new DatagramPacket(sendData, 36, this.serverIPAddress, this.serverPort);
			//  Send leave request packet
			try {
				this.clientSocket.send(sendPacket);
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("Error sending leave request.");
			}	
			//remove channel from vector
			channels.remove(channel);
			
			//update activeChannel if needed
			if(activeChannel.equals(channel)){
				activeChannel = "none";
			}
		}else{
			System.out.println("You are not currently subscribed to channel: "+ channel);
		}
		return;
	}
	
	/*
	 * Exit Chat:  sends logoff request and exits the program
	 */
	public void exitChat(){
		shouldStop = true;
		this.clientSocket.close();
		System.exit(0);
	}
	/*
	 * say:  sends message to server
	 */
	public void say(String message){
		// Make a new ByteBuffer for easily concatenation of byte arrays
		ByteBuffer byteBuffer = ByteBuffer.allocate(100);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);		
byteBuffer.putInt(0,SAY);  // adds the request bytes
		byteBuffer.position(4);
		byte[] channelNameBytes = new byte[32];
		channelNameBytes = this.activeChannel.getBytes();
		byteBuffer.put(channelNameBytes, 0, channelNameBytes.length);  // adds the channel name bytes
		for(int i = 0; i< 32 - channelNameBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] messageBytes = new byte[64];
		if(message.length() > 63){
			message = message.substring(0, 63);
			System.out.println("Messages are limited to 63 characters in length.  Your message has been truncated");
		}
		messageBytes = message.getBytes();
		byteBuffer.put(messageBytes, 0, messageBytes.length);  // adds the message bytes
		for(int i = 0; i< 64 - messageBytes.length; i++){
			byteBuffer.put((byte)00);
		}
		byte[] sendData = new byte[100];
		byteBuffer.rewind();
		byteBuffer.get(sendData);  // gets the byte[] from the buffer and puts it in sendData
		// create a say request packet
		DatagramPacket sendPacket = new DatagramPacket(sendData, 100, this.serverIPAddress, this.serverPort);
		//  Send say request packet
		try {
			this.clientSocket.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error sending say request.");
		}
	}
	public void process(String inpt){
		if(inpt.length() == 0)
			return;
		if(inpt.charAt(0) == '/'){
			String[] req;
			req = inpt.split(" ", 2);
			String request = req[0].toLowerCase();
			if(request.equals("/who")){
				if(req.length < 2)
					System.out.println("Usage: /who <channel name>    example:  /who Common");
				else
					this.who(req[1]);
				return;
			}else if(request.equals("/exit")){
				this.logout();
				this.exitChat();
				return;
			}else if(request.equals("/join")){
				if(req.length < 2)
					System.out.println("Usage: /join <channel name>    example:  /join MyNewChannel");
				else
					this.joinChannel(req[1]);
				return;
			}else if(request.equals("/list")){
				this.listChannels();
				return;
			}else if(request.equals("/leave")){
				if(req.length < 2)
					System.out.println("Usage: /leave <channel name>    example:  /leave Common");
				else
					this.leaveChannel(req[1]);
				return;
			}else if(request.equals("/switch")){
				if(req.length < 2)
					System.out.println("Usage: /switch <channel name>    example:  /switch MyOtherSubscribedChannel");
				else
					this.switchChannels(req[1]);
				return;
			}
		}
		if(this.activeChannel.equals("none")){
			System.out.println("You must switch to an already subscribed channel or join a new one.");
			return;
		}
		this.say(inpt);
	}
	public void digest(byte[] received){
		int s = received.length;
		if(s == 0)
			return;
		ByteBuffer buf = ByteBuffer.allocate(s);
		buf.put(received, 0, s);
		buf.position(0);
		int request = buf.getInt();

	if(request >9)
request = ntohl(request);
		buf.position(4);
		if(request == 0){
			byte[] channelB = new byte[32];
			buf.get(channelB, 0, 32);
			String channel = new String(channelB);
			if(channel.indexOf('\0') == -1)
				channel = channel.substring(0, 30) + '\0';
			else
				channel = channel.substring(0, channel.indexOf('\0'));
			buf.position(36);
			byte[] userB = new byte[32];
			buf.get(userB, 0, 32);
			String user = new String(userB);
			if(user.indexOf('\0') == -1)
				user = user.substring(0, 30) + '\0';
			else
				user = user.substring(0, user.indexOf('\0'));
			buf.position(68);
			byte[] textB = new byte[64];
			buf.get(textB, 0, 64);
			String text = new String(textB);
			if(text.indexOf('\0') == -1)
				text = text.substring(0, 62) + '\0';
			else
				text = text.substring(0, text.indexOf('\0'));
			for(int k = 0; k < 100; k++){
				System.out.print('\b');
			}	
			String output = "["+channel+"]"+"["+user+"]: "+ text;
			System.out.println(output);
			prompt();
				
			return;
		}else if(request == 1){
			int n = buf.getInt(4);
			if(n>9)
				n = ntohl(n);
			if (n == 0){
				System.out.println("There are no existing channels.");
				prompt();
				return;
			}
			System.out.println("Existing Channels:");

			for(int i = 0; i < n; i++){
				buf.position(32*i + 8);
				byte[] channelB = new byte[32];
				buf.get(channelB, 0, 32);
				String channel = new String(channelB);
				if(channel.indexOf('\0') == -1)
					channel = channel.substring(0, 30) +'\0';
				else
					channel = channel.substring(0, channel.indexOf('\0'));
				System.out.println("  "+channel);
			}
			
		
		}else if(request == 2){
			int n = buf.getInt(4);
			if(n>9)
				n = ntohl(n);
			buf.position(8);
			byte[] channelB = new byte[32];
			buf.get(channelB, 0, 32);
			String channel = new String(channelB);
			if(channel.indexOf('\0') == -1)
				channel = channel.substring(0, 30) + '\0';
			else
				channel = channel.substring(0, channel.indexOf('\0'));
			
			if (n == 0){
				System.out.println("There are no users currently in channel "+channel+".");
				prompt();
				return;
			}
			System.out.println("Users of channel "+channel+":");
			for(int i = 1; i <= n; i++){
				buf.position(32*i + 8);
				byte[] userB = new byte[32];
				buf.get(userB, 0, 32);
				String user = new String(userB);
				if(user.indexOf('\0') == -1)
					user = user.substring(0, 30) + '\0';
				else
					user = user.substring(0, user.indexOf('\0'));
				System.out.println("  "+user);
			}
		}else if(request == 3){
			buf.position(4);
			byte[] messageB = new byte[64];
			buf.get(messageB, 0, 64);
			String message = new String(messageB);
			if(message.indexOf('\0') == -1)
				message = message.substring(0, 62) + '\0';
			else
				message = message.substring(0, message.indexOf('\0'));
			System.out.println("ERROR:  "+message);
		}
		prompt();
	}
	
	public class ListenerThread extends Thread{
		public void run(){
			while(!shouldStop){
				byte[] receivedBytes = new byte[1024];
				DatagramPacket receivedPacket = new DatagramPacket(receivedBytes, receivedBytes.length);
				try {
					clientSocket.receive(receivedPacket);
				} catch (IOException e) {
					if(clientSocket.isClosed())
						break;
					e.printStackTrace();
					continue;
				}
				byte[] received = receivedPacket.getData();
				if(shouldStop)
					break;
				digest(received);
			}
		}
	}
	
	
	
	int ntohl(int input)
{
  return
         (input >>> 24) |
         (input >> 8) & 0x0000ff00 |
         (input << 8) & 0x00ff0000 |
         (input << 24);
   
}
	
	
	
}