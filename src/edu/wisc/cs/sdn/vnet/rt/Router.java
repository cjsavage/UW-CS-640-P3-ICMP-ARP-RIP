package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
//import edu.wisc.cs.sdn.vnet.LinkListQueue;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Queue;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.Thread;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device// implements Runnable
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	//private  ArpCache arpCache;
	private static AtomicReference<ArpCache> atomicCache;

	/** Hashmap of queues HOLY SHIT */
	private HashMap<Integer, Queue>  packetQueues; 
	
	private Timer timer;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.atomicCache = new AtomicReference(new ArpCache());
		//this.arpCache = new ArpCache();
		this.packetQueues = new HashMap<Integer, Queue>();
		this.pktBuffer = new LinkedList<Ethernet>();
		this.ifaceBuffer = new LinkedList<Iface>();
	}
	
	public boolean havePkt()
	{ return !this.pktBuffer.isEmpty(); }
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	public void createRouteTable()
	{
		for (Iface iface : this.interfaces.values())
		{
			int maskIp = iface.getSubnetMask();
			int dstIp = iface.getIpAddress() & maskIp;
			this.routeTable.insert(dstIp, 0, maskIp, iface, 1);
		}
		System.out.println(this.routeTable.toString());
		
		// Send initial RIP update request
		for (Iface iface : this.interfaces.values())
		{
			this.sendRipPacket(iface, true, true);
		}
		
		this.timer = new Timer();
		this.timer.scheduleAtFixedRate(new updateRIP(), 10000, 10000);
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!atomicCache.get().load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		//System.out.print(this.arpCache.toString());
		System.out.println(this.atomicCache.get().toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* TODO: Handle packets                                             */
		
		switch(etherPacket.getEtherType())
		{
		case Ethernet.TYPE_IPv4:
			this.handleIpPacket(etherPacket, inIface);
			break;
		case Ethernet.TYPE_ARP:
			this.handleARPPacket(etherPacket, inIface);
			break;
		// Ignore all other packet types, for now
		}
		
		/********************************************************************/
	}
	
	private void sendError(Ethernet etherPacket, Iface inIface, int type, int code, boolean echo){
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		ICMP icmp = new ICMP();
		Data data = new Data();
		ether.setPayload(ip);
		ip.setPayload(icmp);
		icmp.setPayload(data);	

		ether.setEtherType(Ethernet.TYPE_IPv4);
		IPv4 IpPacket = (IPv4)(etherPacket.getPayload());

		int payLoadLen = (int)(((IPv4)(etherPacket.getPayload())).getTotalLength());
		byte original[] = IpPacket.serialize();	
		byte dataBytes[] = new byte[4 + (echo ? payLoadLen : IpPacket.getHeaderLength() * 4 + 8)];

		//System.out.println("echo: "+echo+ " lens: "+original.length+" | "+dataBytes.length);
	
		for( int i = 0; i < (echo ? payLoadLen : (IpPacket.getHeaderLength() * 4 + 8)); i++)
			dataBytes[i + 4] = original[i];
		data.setData(dataBytes);
		
		byte d = 64;
		ip.setTtl(d);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		//ip.setSourceAddress(inIface.getIpAddress());
		ip.setDestinationAddress(((IPv4)(etherPacket.getPayload())).getSourceAddress());

		icmp.setIcmpType((byte)type);
		icmp.setIcmpCode((byte)code);
	
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		int dstAddr = ipPacket.getSourceAddress();
		RouteEntry bestMatch = this.routeTable.lookup(dstAddr);
		if (null == bestMatch)
		{ return; }
		// Make sure we don't sent a packet back out the interface it came in
        	Iface outIface = bestMatch.getInterface();
        	//if (outIface == inIface)
        	//{ return; }
		ip.setSourceAddress(echo ? ipPacket.getDestinationAddress() : outIface.getIpAddress());

        	// Set source MAC address in Ethernet header
        	ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		
        	// If no gateway, then nextHop is IP destination
        	int nextHop = bestMatch.getGatewayAddress();
        	if (0 == nextHop)
        	{ nextHop = dstAddr; }

        	// Set destination MAC address in Ethernet header
        	ArpEntry arpEntry = this.atomicCache.get().lookup(nextHop);
        	if (null == arpEntry)
        	{ return; }
        	ether.setDestinationMACAddress(arpEntry.getMac().toBytes());

		System.out.println("sent packet:" + ether);
        	this.sendPacket(ether, outIface);
	}

	private void handleARPPacket(Ethernet etherPacket, Iface inIface)
	{
		if (etherPacket.getEtherType() != Ethernet.TYPE_ARP)
                {
                                return;
                }

                // Get IP header
                ARP arpPacket = (ARP)etherPacket.getPayload();
        	System.out.println("Handle ARP packet, op code: "+arpPacket.getOpCode());

		if(arpPacket.getOpCode() != ARP.OP_REQUEST){
			if(arpPacket.getOpCode() == ARP.OP_REPLY){
				ByteBuffer senderProtocol = ByteBuffer.wrap(arpPacket.getSenderProtocolAddress());
				int address = senderProtocol.getInt();
				atomicCache.get().insert(new MACAddress(arpPacket.getSenderHardwareAddress()), address);					
			
				//System.out.println("IP addr we're looking at:" + address);
	
				Queue packetsToSend = packetQueues.get(new Integer(address));
				while(packetsToSend != null && packetsToSend.peek() != null){
					Ethernet packet = (Ethernet)packetsToSend.poll();
					packet.setDestinationMACAddress(arpPacket.getSenderHardwareAddress());
					this.sendPacket(packet, inIface);
				}

			}else
				return;
		}

		//System.out.println("Target Protocol addr: "+ByteBuffer.wrap(arpPacket.getSenderProtocolAddress()).getShort());
		//System.out.println("orignal arp: "+arpPacket);

		int targetIp = ByteBuffer.wrap(arpPacket.getTargetProtocolAddress()).getInt();
        	if (targetIp != inIface.getIpAddress())
			return;

		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());	
		ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());
		
		ARP arp = new ARP();
		arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
		arp.setProtocolType(ARP.PROTO_TYPE_IP);
		arp.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH);
		arp.setProtocolAddressLength((byte)4);
		arp.setOpCode(ARP.OP_REPLY);
		arp.setSenderHardwareAddress(inIface.getMacAddress().toBytes());
		arp.setSenderProtocolAddress(inIface.getIpAddress());
		arp.setTargetHardwareAddress(arpPacket.getSenderHardwareAddress());
		arp.setTargetProtocolAddress(arpPacket.getSenderProtocolAddress());

		ether.setPayload(arp);
		ether.serialize();        	

		System.out.println("Sending ARP PACKET********\n"+ether+"\n*******************");

		this.sendPacket(ether, inIface);
		return;
	}
	
	private void handleRipPacket(Ethernet etherPacket, Iface inIface)
	{
		// Check headers for conformance with RIPv2
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
		IPv4 ip = (IPv4)etherPacket.getPayload();
		if (ip.getProtocol() != IPv4.PROTOCOL_UDP)
		{ return; }
		UDP udp = (UDP)ip.getPayload();
		// Verify UDP checksum
		short origCksum = udp.getChecksum();
        udp.resetChecksum();
        byte[] serialized = udp.serialize();
        udp.deserialize(serialized, 0, serialized.length);
        short calcCksum = udp.getChecksum();
        if (origCksum != calcCksum)
        { return; }
		// Verify UDP port
		if (udp.getDestinationPort() != UDP.RIP_PORT)
		{ return; }
		
		RIPv2 rip = (RIPv2)udp.getPayload();
		if (rip.getCommand() == RIPv2.COMMAND_REQUEST)
		{
			if (etherPacket.getDestinationMAC().toLong() == MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toLong())
			{
				if (ip.getDestinationAddress() != IPv4.toIPv4Address("224.0.0.9"))
				{
					System.out.println("Incorrect RIP request format");
					return;
				}
				this.sendRipPacket(inIface, true, false);
				return;
			}
			else
			{
				System.out.println("Incorrect RIP request format");
				return;
			}
		}
		else
		{
			if (etherPacket.getDestinationMAC().toLong() == inIface.getMacAddress().toLong())
			{
				if (ip.getDestinationAddress() != inIface.getIpAddress())
				{
					System.out.println("Incorrect RIP request format");
					return;
				}
			}
			if (etherPacket.getDestinationMAC().toLong() == MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toLong())
			{
				if (ip.getDestinationAddress() != IPv4.toIPv4Address("224.0.0.9"))
				{
					System.out.println("Incorrect RIP request format");
					return;
				}
			}
		}
		
		// RIP packet is a response
		boolean updated = false;
		
		for (RIPv2Entry ripEntry : rip.getEntries())
		{
			int addr = ripEntry.getAddress();
			int mask = ripEntry.getSubnetMask();
			int cost = ripEntry.getMetric() + 1;
			int hop = ripEntry.getNextHopAddress();
			ripEntry.setMetric(cost);
			RouteEntry entry = this.routeTable.lookup(addr);
			if (null == entry)
			{
				// TODO
				this.routeTable.insert(addr, hop, mask, inIface, cost);
				updated = true;
			}
			else if (entry.getCost() > cost)
			{
				// TODO
				this.routeTable.update(addr, hop, mask, inIface, cost);
				updated = true;
			}
		}
		
		if (updated)
		{
			System.out.println("Route Table updated");
			System.out.println(this.routeTable.toString());
			// send updates on each interface
			for (Iface entry : this.interfaces.values())
			{
				this.sendRipPacket(inIface, false, false);
			}
		}
		else
		{
			System.out.println("Route table stayed the same");
		}
	}
	
	public void sendRipPacket(Iface inIface, boolean broadcast, boolean request)
	{
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		UDP udp = new UDP();
		RIPv2 rip = new RIPv2();
		ether.setPayload(ip);
		ip.setPayload(udp);
		udp.setPayload(rip);
		
		// Set ethernet fields
		ether.setEtherType(Ethernet.TYPE_IPv4);
		if (broadcast)
		{
			ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
			ip.setDestinationAddress("224.0.0.9");
		}
		else
		{
			ether.setDestinationMACAddress(inIface.getMacAddress().toBytes());
			ip.setDestinationAddress(inIface.getIpAddress());
		}
		ether.setSourceMACAddress("FF:FF:FF:FF:FF:FF");
		
		// Set IP fields
		ip.setVersion((byte)4);
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setTtl((byte)64);
		
		// Set UDP fields
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setSourcePort(UDP.RIP_PORT);
		
		// Set RIP fields
		if (request)
		{
			rip.setCommand(RIPv2.COMMAND_REQUEST);
		}
		else
		{
			rip.setCommand(RIPv2.COMMAND_RESPONSE);
		}
		
		// Add all subnets
		for (RouteEntry entry : this.routeTable.getAll())
		{
			int address = entry.getDestinationAddress();
			int mask = entry.getMaskAddress();
			int hop = inIface.getIpAddress();
			int cost = entry.getCost();
			
			RIPv2Entry ripEntry = new RIPv2Entry(address, mask, cost);
			ripEntry.setNextHopAddress(hop);
			rip.addEntry(ripEntry);
		}
		
		ether.serialize();
		this.sendPacket(ether, inIface);
		return;
	}
	
	public void timedRipResponse()
	{
		for (Iface iface : this.interfaces.values())
		{
			this.sendRipPacket(iface, true, false);
		}
		return;
	}
	
	class updateRIP extends TimerTask
	{
		public void run()
		{
			timedRipResponse();
		}
	}
	
	private void handleIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ 
			return; 
		}
		
		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
        System.out.println("Handle IP packet");

        // Verify checksum
        short origCksum = ipPacket.getChecksum();
        ipPacket.resetChecksum();
        byte[] serialized = ipPacket.serialize();
        ipPacket.deserialize(serialized, 0, serialized.length);
        short calcCksum = ipPacket.getChecksum();
        if (origCksum != calcCksum)
        { return; }
        
        // Check TTL
        ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
        if (0 == ipPacket.getTtl())
        {
		this.sendError(etherPacket, inIface, 11, 0, false);
		return; 
	}
        
        // Reset checksum now that TTL is decremented
        ipPacket.resetChecksum();
        
        // Check if packet is destined for one of router's interfaces
        for (Iface iface : this.interfaces.values())
        {
        	if (ipPacket.getDestinationAddress() == iface.getIpAddress())
        	{ 
			byte protocol = ipPacket.getProtocol();
			System.out.println("ipPacket protol: "+protocol);
			if(protocol == IPv4.PROTOCOL_UDP){
				UDP udpTemp = (UDP)ipPaccket.getPayload();
				if (udpTemp.getDestinationPort() == UDP.RIP_PORT){
					this.handleRipPacket(etherPacket, inIface);
				}else{
					this.sendError(etherPacket, inIface, 3, 3, false);
				}
			} else if(protocol == IPv4.PROTOCOL_TCP){
				this.sendError(etherPacket, inIface, 3, 3, false);
			}else if(protocol == IPv4.PROTOCOL_ICMP){
				ICMP icmp = (ICMP)ipPacket.getPayload();
				if(icmp.getIcmpType() == 8){
					//System.out.println("echoing");
					this.sendError(etherPacket, inIface, 0, 0, true);
				}				
			}
			return; 
		}
        }
		
        // Do route lookup and forward
        this.forwardIpPacket(etherPacket, inIface);
	}

    private void forwardIpPacket(Ethernet etherPacket, Iface inIface)
    {
        // Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
        System.out.println("Forward IP packet");
		
		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
        int dstAddr = ipPacket.getDestinationAddress();

        // Find matching route table entry 
        RouteEntry bestMatch = this.routeTable.lookup(dstAddr);

        // If no entry matched, do nothing
        if (null == bestMatch)
        { 
		this.sendError(etherPacket, inIface, 3, 0, false);
		return; 
	}

        // Make sure we don't sent a packet back out the interface it came in
        Iface outIface = bestMatch.getInterface();
        if (outIface == inIface)
        { return; }

        // Set source MAC address in Ethernet heade
	MACAddress out = outIface.getMacAddress();
        etherPacket.setSourceMACAddress(out.toBytes());

        // If no gateway, then nextHop is IP destination
        int nextHop = bestMatch.getGatewayAddress();
        if (0 == nextHop)
        { nextHop = dstAddr; }

        // Set destination MAC address in Ethernet header
        ArpEntry arpEntry = this.atomicCache.get().lookup(nextHop);
        if (null == arpEntry)
        { 
		ARP arp = new ARP();
                arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
                arp.setProtocolType(ARP.PROTO_TYPE_IP);
                arp.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH);
                arp.setProtocolAddressLength((byte)4);
                arp.setOpCode(ARP.OP_REQUEST);
                arp.setSenderHardwareAddress(inIface.getMacAddress().toBytes());
                arp.setSenderProtocolAddress(inIface.getIpAddress());
                arp.setTargetHardwareAddress(ByteBuffer.allocate(8).putInt(0).array());
                arp.setTargetProtocolAddress(nextHop);


		final AtomicReference<Ethernet> atomicEtherPacket = new AtomicReference(new Ethernet());
		final AtomicReference<Iface> atomicIface = new AtomicReference(outIface);
		final AtomicReference<Ethernet> atomicInPacket = new AtomicReference(etherPacket);
		//Ethernet ether = new Ethernet();
		atomicEtherPacket.get().setEtherType(Ethernet.TYPE_ARP);
		atomicEtherPacket.get().setSourceMACAddress(inIface.getMacAddress().toBytes());	

                atomicEtherPacket.get().setPayload(arp);
		atomicEtherPacket.get().setDestinationMACAddress("FF:FF:FF:FF:FF:FF");	
		atomicEtherPacket.get().serialize();

		Integer next = new Integer(nextHop);

		if(!packetQueues.containsKey(next)){
			packetQueues.put(next, new LinkedList());
			System.out.println("making new one");
		}	
		Queue nextHopQueue = packetQueues.get(next);
		nextHopQueue.add(etherPacket);

		final AtomicReference<Queue> atomicQueue = new AtomicReference(nextHopQueue);

		//System.out.println("Sending packets for: "+nextHop);
		final int nextH = nextHop;	

		Thread waitForReply = new Thread(new Runnable(){
			

    			public void run() {
	
        			try {
					System.out.println("Sending ARP PACKET********\n"+atomicEtherPacket.get()+"\n*******************");
					sendPacket(atomicEtherPacket.get(), atomicIface.get());
            				//System.out.println("1) Checking for "+nextH);
					Thread.sleep(1000);
					if(atomicCache.get().lookup(nextH) != null){
						System.out.println("Found it!");
						return;
					}
					System.out.println("Sending ARP PACKET********\n"+atomicEtherPacket.get()+"\n*******************");
					sendPacket(atomicEtherPacket.get(), atomicIface.get());
					//System.out.println("2) Checking again for" + nextH);
            				Thread.sleep(1000);                
                                        if(atomicCache.get().lookup(nextH) != null){
                                                System.out.println("Found it!");
                                                return;
                                        }
					System.out.println("Sending ARP PACKET********\n"+atomicEtherPacket.get()+"\n*******************");
					sendPacket(atomicEtherPacket.get(), atomicIface.get());
					//System.out.println("3) Checking again for" + nextH);
        				Thread.sleep(1000);
                                        if(atomicCache.get().lookup(nextH) != null){
                                                System.out.println("Found it!");
                                                return;
                                        }

					while(atomicQueue.get() != null && atomicQueue.get().peek() != null){
                                        	atomicQueue.get().poll();
                                	}
					sendError(atomicInPacket.get(), atomicIface.get(), 3, 1, false);
					return;
				} catch(InterruptedException v) {
           				 System.out.println(v);
        			}
    			}  
		});
		waitForReply.start();
		return; 
	}
	else //added
        	etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
        
        this.sendPacket(etherPacket, outIface);
    }

}

