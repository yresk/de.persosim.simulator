package de.persosim.simulator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import de.persosim.simulator.jaxb.PersoSimJaxbContextProvider;
import de.persosim.simulator.perso.DefaultPersoTestPki;
import de.persosim.simulator.perso.Personalization;

/**
 * This class provides access to and control of the actual simulator. It can be
 * used to start, stop and configure it. The simulator may be configured by
 * providing either command line arguments during start-up or user initiated
 * commands at runtime. Currently both options only allow for a single command
 * to be executed parameterized by at most one command argument. As all
 * parameters vital for the operation of the simulator are implicitly set to
 * default values by fall-through, no explicit configuration is required.
 * 
 * @author slutters
 * 
 */
public class PersoSim implements Runnable {
	
	private SocketSimulator simulator;
	
	/*
	 * This variable holds the currently used personalization.
	 * It may explicitly be null and should not be read directly from here.
	 * As there exist several ways of providing a personalization of which none at all may be used the variable may remain null/unset.
	 * Due to this possibility access to this variable should be performed by calling the getPersonalization() method. 
	 */
	private Personalization currentPersonalization;
	
	public static final String CMD_START                      = "start";
	public static final String CMD_RESTART                    = "restart";
	public static final String CMD_STOP                       = "stop";
	public static final String CMD_EXIT                       = "exit";
	public static final String CMD_SET_PORT                   = "setport";
	public static final String ARG_SET_PORT                   = "-port";
	public static final String CMD_LOAD_PERSONALIZATION       = "loadperso";
	public static final String ARG_LOAD_PERSONALIZATION       = "-perso";
	public static final String CMD_SEND_APDU                  = "sendapdu";
	public static final String CMD_HELP                       = "help";
	public static final String ARG_HELP                       = "-h";
	public static final String CMD_CONSOLE_ONLY               = "--consoleOnly";
	
	public static final int DEFAULT_SIM_PORT = 9876;
	public static final String DEFAULT_SIM_HOST = "localhost";
	
	private int simPort = DEFAULT_SIM_PORT; // default
	private boolean executeUserCommands = false;
	
	public PersoSim(String... args) {
		Security.addProvider(new BouncyCastleProvider());
		
		try {
			handleArgs(args);
		} catch (IllegalArgumentException e) {
			System.out.println("simulation aborted, reason is: " + e.getMessage());
		}
		
	}

	/**
	 * Default command line main method.
	 * 
	 * This starts the PersoSim simulator within its own thread and accepts user
	 * commands to control it on the existing thread on a simple command prompt.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		(new PersoSim(args)).run();
	}

	@Override
	public void run() {
		System.out.println("Welcome to PersoSim");

		startSimulator();
		handleUserCommands();
	}
	
	public static void showExceptionToUser(Exception e) {
		System.out.println("Exception: " + e.getMessage());
		e.printStackTrace();
	}
	
	/**
	 * This method parses the provided String object for commands and possible
	 * arguments. First the provided String is trimmed. If the String is empty,
	 * the returned array will be of length 0. If the String does not contain at
	 * least one space character ' ', the whole String will be returned as first
	 * and only element of an array of length 1. If the String does contain at
	 * least one space character ' ', the substring up to but not including the
	 * position of the first occurrence will be the first element of the
	 * returned array. The rest of the String will be trimmed and, if not of
	 * length 0, form the second array element.
	 * 
	 * IMPL extend to parse for multiple arguments add recognition of "
	 * characters as indication of file names allowing white spaces in between.
	 * 
	 * @param args
	 *            the argument String to be parsed
	 * @return the parsed arguments
	 */
	public static String[] parseCommand(String args) {
		String argsInput = args.trim().toLowerCase();
		
		int index = argsInput.indexOf(" ");
		
		if(index >= 0) {
			String cmd = argsInput.substring(0, index);
			String params = argsInput.substring(index).trim();
			return new String[]{cmd, params};
		} else{
			if(argsInput.length() > 0) {
				return new String[]{argsInput};
			} else{
				return new String[0];
			}
		}
	}
	
	//ok
	/**
	 * This method handles instantiation and (re)start of the SocketSimulator.
	 */
	private boolean startSimulator() {
		simulator = new SocketSimulator(getPersonalization(), simPort);
		
		if (simulator.isRunning()) {
			return false;
		} else{
			return simulator.start();
		}

	}
	
	public boolean cmdStartSimulator(List<String> args) {
		if((args != null) && (args.size() >= 1)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_START)) {
				args.remove(0);
				return startSimulator();
			}
		}
		
		return false;
	}
	
	public boolean cmdStopSimulator(List<String> args) {
		if((args != null) && (args.size() >= 1)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_STOP)) {
				args.remove(0);
				return stopSimulator();
			}
		}
		
		return false;
	}
	
	
	
//	private boolean cmdLoadPersonalization(List<String> args) {
//		if(args.size() >= 2) {
//    		try{
//    			currentPersonalization = parsePersonalization(args.get(1));
//				
//    			args.remove(0);
//    			args.remove(0);
//    			
//    			if(executeUserCommands) {
//    				return true;
//    			} else{
//    				return restartSimulator();
//    			}
//    		} catch(FileNotFoundException | JAXBException e) {
//    			System.out.println("unable to set personalization, reason is: " + e.getMessage());
//    		}
//    	} else{
//    		System.out.println("set personalization command requires one single file name");
//    	}
//		
//		stopSimulator();
//		System.out.println("simulation is stopped");
//		args.remove(0);
//		return false;
//	}
	
	
	
	private boolean restartSimulator() {
		stopSimulator();
		return startSimulator();
	}
	
	public boolean cmdRestartSimulator(List<String> args) {
		if((args != null) && (args.size() >= 1)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_RESTART)) {
				args.remove(0);
				return restartSimulator();
			}
		}
		
		return false;
	}
	
	public boolean cmdExitSimulator(List<String> args) {
		if((args != null) && (args.size() >= 1)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_EXIT)) {
				args.remove(0);
				executeUserCommands = false;
				return stopSimulator();
			}
		}
		
		return false;
	}

	/**
	 * This method returns the content of {@link #currentPersonalization}, the
	 * currently used personalization. If no personalization is set, i.e. the
	 * variable is null, it will be set to the default personalization which
	 * will be returned thereafter. This mode of accessing personalization
	 * opportunistic assumes that a personalization will always be set and
	 * generating a default personalization is an overhead only to be spent as a
	 * measure of last resort.
	 * 
	 * @return the currently used personalization
	 */
	public Personalization getPersonalization() {
		if(currentPersonalization == null) {
			System.out.println("Loading default personalization");
			currentPersonalization = new DefaultPersoTestPki();
		}
		
		return currentPersonalization;
	}
	
	/**
	 * This method parses a {@link Personalization} object from a file identified by its name.
	 * @param persoFileName the name of the file to contain the personalization
	 * @return the parsed personalization
	 * @throws FileNotFoundException 
	 * @throws JAXBException if parsing of personalization not successful
	 */
	public static Personalization parsePersonalization(String persoFileName) throws FileNotFoundException, JAXBException {
		File persoFile = new File(persoFileName);
		
		Unmarshaller um = PersoSimJaxbContextProvider.getContext().createUnmarshaller();
		System.out.println("Parsing personalization from file " + persoFileName);
		return (Personalization) um.unmarshal(new FileReader(persoFile));
	}
	
	/**
	 * This method sets a new port for the simulator.
	 * In order for the changes to take effect, the simulator needs to be restarted.
	 * @param newPortString the new port to be used
	 */
	public void setPort(String newPortString) {
		if(newPortString == null) {throw new NullPointerException("port parameter must not be null");}
		int newPort = Integer.parseInt(newPortString);
		if(newPort < 0) {throw new IllegalArgumentException("port number must be positive");}
		
		simPort = newPort;
		
		System.out.println("new port set to " + newPort + " after restart of simulation.");
		
		//IMPL check for port being unused
	}
	
	//ok
	/**
	 * Stops the simulator thread and returns when the thread is stopped.
	 */
	private boolean stopSimulator() {
		boolean simStopped = false;
		
		if (simulator != null) {
			simStopped = simulator.stop();
			simulator = null;
		}
		
		return simStopped;
	}

	/**
	 * Transmit an APDU to the card
	 * 
	 * @param cmd
	 *            string containing the command
	 */
	private String sendCmdApdu(String cmd) {
		cmd = cmd.trim();

		Pattern cmdSendApduPattern = Pattern
				.compile("^send[aA]pdu ([0-9a-fA-F\\s]+)$");
		Matcher matcher = cmdSendApduPattern.matcher(cmd);
		if (!matcher.matches()) {
			throw new RuntimeException("invalid arguments to sendApdu");
		}
		String apdu = matcher.group(1);
		return exchangeApdu(apdu);

	}
	
	//ok
	/**
	 * This method processes the send APDU command according to the provided arguments.
	 * @param args the arguments provided for processing
	 * @return whether processing has been successful
	 */
	public String cmdSendApdu(List<String> args) {
		if((args != null) && (args.size() >= 2)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_SEND_APDU)) {
				String result;
				
				try{
	    			result = sendCmdApdu("sendApdu " + args.get(1));
	    			args.remove(0);
	    			args.remove(0);
	    			return result;
	    		} catch(RuntimeException e) {
	    			result = "unable to set personalization, reason is: " + e.getMessage();
	    			args.remove(0);
	    			return result;
	    		}
			}
		}
		
		return "unable to process command";
	}
	
	/**
	 * Transmit the given APDU to the simulator, which processes it and returns
	 * the response. The response APDU is received from the simulator via its
	 * socket interface and returned to the caller as HexString.
	 * 
	 * @param cmdApdu
	 *            HexString containing the CommandAPDU
	 * @return
	 */
	private String exchangeApdu(String cmdApdu) {
		return exchangeApdu(cmdApdu, DEFAULT_SIM_HOST, simPort);
	}

	/**
	 * Transmit the given APDU to the simulator identified by host name and port
	 * number, where it will be processed and answered by a response. The
	 * response APDU is received from the simulator via its socket interface and
	 * returned to the caller as HexString.
	 * 
	 * @param cmdApdu
	 *            HexString containing the CommandAPDU
	 * @param host
	 *            the host to contact
	 * @param port
	 *            the port to query
	 * @return
	 */
	//FIXME SLS why is this new method needed?
	private String exchangeApdu(String cmdApdu, String host, int port) {
		cmdApdu = cmdApdu.replaceAll("\\s", ""); // remove any whitespace

		Socket socket;
		try {
			socket = new Socket(host, port);
		} catch (IOException e) {
			socket = null;
			showExceptionToUser(e);
			return null;
		}

		PrintStream out = null;
		BufferedReader in = null;
		try {
			out = new PrintStream(socket.getOutputStream());
			in = new BufferedReader(new InputStreamReader(
					socket.getInputStream()));
		} catch (IOException e) {
			showExceptionToUser(e);
		}

		out.println(cmdApdu);
		out.flush();

		String respApdu = null;
		try {
			respApdu = in.readLine();
		} catch (IOException e) {
			showExceptionToUser(e);
		} finally {
			System.out.println("> " + cmdApdu);
			System.out.println("< " + respApdu);
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					showExceptionToUser(e);
				}
			}
		}

		return respApdu;

	}
	
	/**
	 * This method prints the help menu to the command line.
	 */
	private boolean printHelpArgs() {
		System.out.println("Available commands:");
		System.out.println(ARG_LOAD_PERSONALIZATION + " <file name>");
		System.out.println(ARG_SET_PORT + " <port number>");
		System.out.println(ARG_HELP);
		return true;
	}
	
	/**
	 * This method prints the help menu to the user command line.
	 */
	private boolean printHelpCmd() {
		System.out.println("Available commands:");
		System.out.println(CMD_SEND_APDU + " <hexstring>");
		System.out.println(CMD_LOAD_PERSONALIZATION + " <file name>");
		System.out.println(CMD_SET_PORT + " <port number>");
		System.out.println(CMD_START);
		System.out.println(CMD_RESTART);
		System.out.println(CMD_STOP);
		System.out.println(CMD_EXIT);
		System.out.println(CMD_HELP);
		return true;
	}
	
	//ok
	/**
	 * This method processes the load personalization command according to the provided arguments.
	 * @param args the arguments provided for processing
	 * @return whether processing has been successful
	 */
	public boolean cmdLoadPersonalization(List<String> args) {
		if((args != null) && (args.size() >= 2)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_LOAD_PERSONALIZATION) || cmd.equals(ARG_LOAD_PERSONALIZATION)) {
				try{
	    			currentPersonalization = parsePersonalization(args.get(1));
					
	    			args.remove(0);
	    			args.remove(0);
	    			
	    			if(executeUserCommands) {
	    				return restartSimulator();
	    			} else{
	    				return true;
	    			}
	    		} catch(FileNotFoundException | JAXBException e) {
	    			System.out.println("unable to set personalization, reason is: " + e.getMessage());
	    			stopSimulator();
	    			System.out.println("simulation is stopped");
	    			args.remove(0);
	    			return false;
	    		}
			}
		}
		
		return false;
	}
	
	//ok
	/**
	 * This method processes the set port command according to the provided arguments.
	 * @param args the arguments provided for processing
	 * @return whether processing has been successful
	 */
	public boolean cmdSetPortNo(List<String> args) {
		if((args != null) && (args.size() >= 2)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_SET_PORT) || cmd.equals(ARG_SET_PORT)) {
				try{
	    			setPort(args.get(1));
	    			
	    			args.remove(0);
	    			args.remove(0);
	    			
	    			if(executeUserCommands) {
	    				return true;
	    			} else{
	    				return restartSimulator();
	    			}
	    		} catch(IllegalArgumentException | NullPointerException e) {
	    			System.out.println("unable to set port, reason is: " + e.getMessage());
	    			args.remove(0);
	    			return false;
	    		}
			}
		}
		
		return false;
	}
	
	//ok
	/**
	 * This method implements the behavior of the user command prompt. E.g.
	 * prints the prompt, reads the user commands and forwards this to the the
	 * execution method for processing. Only one command per invocation of the
	 * execution method is allowed. The first argument provided must be the
	 * command, followed by an arbitrary number of parameters. If the number of
	 * provided parameters is higher than the number expected by the command,
	 * the surplus parameters will be ignored.
	 */
	private void handleUserCommands() {
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

		PrintStream	origOut	= System.out;
		
		executeUserCommands = true;
		while (executeUserCommands) {
			System.out.println("PersoSim commandline: ");
			origOut.println("still alive");
			origOut.flush();
			String cmd = null;
			try {
				System.out.println("read cmd");
				cmd = br.readLine();
				System.out.println("cmd is: " + cmd);
			} catch (IOException e) {
				e.printStackTrace();
				origOut.println("somethign wrong");
				origOut.flush();
			}
			try {
				if (cmd != null) {
					cmd = cmd.trim();
					System.out.println("new cmd: " + cmd);
					String[] args = parseCommand(cmd);
					executeUserCommands(args);
				}
			} catch (RuntimeException e) {
				showExceptionToUser(e);
			}
		}
	}
	
	public boolean cmdHelp(List<String> args) {
		if((args != null) && (args.size() >= 1)) {
			String cmd = args.get(0);
			
			if(cmd.equals(CMD_HELP) || cmd.equals(ARG_HELP)) {
				args.remove(0);
				
				if(executeUserCommands) {
					return printHelpCmd();
				} else{
					return printHelpArgs();
				}
			}
		}
		
		return false;
	}
	
	public void executeUserCommands(String cmd) {
		String trimmedCmd = cmd.trim();
		System.out.println("new cmd: " + trimmedCmd);
		String[] args = parseCommand(trimmedCmd);
		
		executeUserCommands(args);
	}
	
	/**
	 * This method implements the execution of commands initiated by user interaction.
	 * @param args the parsed commands and arguments
	 */
	public void executeUserCommands(String... args) {
		if((args == null) || (args.length == 0)) {return;}
		
		List<String> currentArgs = Arrays.asList(args);
		// the list returned by Arrays.asList() does not support optional but required remove operation
		currentArgs = new ArrayList<String>(currentArgs);
		
		for(int i = currentArgs.size() - 1; i >= 0; i--) {
			if(currentArgs.get(i) == null) {
				currentArgs.remove(i);
			}
		}
		
		int noOfArgsWhenChekedLast = currentArgs.size();
		while(currentArgs.size() > 0) {
			cmdLoadPersonalization(currentArgs);
			cmdSetPortNo(currentArgs);
			cmdSendApdu(currentArgs);
			cmdStartSimulator(currentArgs);
			cmdRestartSimulator(currentArgs);
			cmdStopSimulator(currentArgs);
			cmdExitSimulator(currentArgs);
			cmdHelp(currentArgs);
			
			if(noOfArgsWhenChekedLast == currentArgs.size()) {
				//first command in queue has not been processed
				String currentArgument = currentArgs.get(0);
				System.out.println("unrecognized argument \"" + currentArgument + "\" will be ignored");
				currentArgs.remove(0);
			}
		}
		
	}
	
	/**
	 * This method implements the execution of commands initiated by command line arguments.
	 * @param args the parsed commands and arguments
	 */
	public void handleArgs(String... args) {
		if((args == null) || (args.length == 0)) {return;}
		
		List<String> currentArgs = Arrays.asList(args);
		// the list returned by Arrays.asList() does not support optional but required remove operation
		currentArgs = new ArrayList<String>(currentArgs);
		
		while(currentArgs.size() > 0) {
			String currentArgument = currentArgs.get(0);
			switch (currentArgument) {
		        case ARG_LOAD_PERSONALIZATION:
		        	cmdLoadPersonalization(currentArgs);
		        	break;
		        case ARG_SET_PORT:
		        	cmdSetPortNo(currentArgs);
		        	break;
		        case ARG_HELP:
	            	printHelpArgs();
	            	currentArgs.remove(0);
					break;
		        case CMD_CONSOLE_ONLY:
		        	// do no actual processing, i.e. prevent simulator from logging unknown command error as command has already been processed
		        	// command is passed on as part of unprocessed original command line arguments
		        	currentArgs.remove(0);
		        	break;
		        default:
		        	System.out.println("unrecognized command or parameter \"" + currentArgument + "\" will be ignored");
		        	currentArgs.remove(0);
		            break;
			}
		}
		
	}

}
