/*******************************************************************************
 * Copyright (c) 2019-2021 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.epics.pva.server;

import static org.epics.pva.PVASettings.logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.logging.Level;

import org.epics.pva.PVASettings;
import org.epics.pva.common.AddressInfo;
import org.epics.pva.common.Network;
import org.epics.pva.common.PVAHeader;
import org.epics.pva.common.SearchRequest;
import org.epics.pva.common.UDPHandler;
import org.epics.pva.data.Hexdump;
import org.epics.pva.data.PVAAddress;
import org.epics.pva.data.PVABool;
import org.epics.pva.data.PVAString;

/** Listen to search requests, send beacons
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class ServerUDPHandler extends UDPHandler
{
    private final SearchHandler search_handler;

    /** UDP channels on which we listen to name searches,
     *  reply to them,
     *  and on which we send beacons.
     *
     *  To reply/send to both IPv4 and IPv6, we need one
     *  channel per protocol family.
     */
    private volatile DatagramChannel udp4, udp6;

    /** Local multicast used to re-send IPv4 unicasts */
    private volatile AddressInfo local_multicast = null;

    private final ByteBuffer send_buffer = ByteBuffer.allocate(PVASettings.MAX_UDP_PACKET);

    private volatile Thread listen_thread4 = null;
    private volatile Thread listen_thread6 = null;


    /** Start handling UDP search requests
     *  @param search_handler Callback for received name searches
     *  @throws Exception on error
     */
    public ServerUDPHandler(final SearchHandler search_handler) throws Exception
    {
        this.search_handler = search_handler;

        for (AddressInfo info : Network.parseAddresses(PVASettings.EPICS_PVAS_INTF_ADDR_LIST))
        {
            // First should be non-multicast addresses that create the IPv4 and/or IPv6 channel
            if (! info.getAddress().getAddress().isMulticastAddress())
            {
                if (info.isIPv4())
                {
                    if (udp4 != null)
                        throw new Exception("EPICS_PVAS_INTF_ADDR_LIST has more than one IPv4 address");
                    udp4 = Network.createUDP(StandardProtocolFamily.INET, info.getAddress().getAddress(), PVASettings.EPICS_PVAS_BROADCAST_PORT);
                    logger.log(Level.FINE, "Awaiting searches and sending beacons on UDP " + info);
                }

                if (info.isIPv6())
                {
                    if (udp6 != null)
                        throw new Exception("EPICS_PVAS_INTF_ADDR_LIST has more than one IPv4 address");
                    udp6 = Network.createUDP(StandardProtocolFamily.INET6, info.getAddress().getAddress(), PVASettings.EPICS_PVAS_BROADCAST_PORT);
                    logger.log(Level.FINE, "Awaiting searches and sending beacons on UDP " + info);
                }
            }
            else
            {
                // Have channel (which must already exist) join multicast group
                if (info.getInterface() == null)
                    throw new Exception("EPICS_PVAS_INTF_ADDR_LIST contains multicast group without interface");
                if (info.isIPv4())
                {
                    if (udp4 == null)
                        throw new Exception("EPICS_PVAS_INTF_ADDR_LIST lacks IPv4 address, cannot add multicast");
                    udp4.join(info.getAddress().getAddress(), info.getInterface());
                    logger.log(Level.FINE, "Listening to UDP multicast " + info);
                    local_multicast = info;
                }
                if (info.isIPv6())
                {
                    if (udp6 == null)
                        throw new Exception("EPICS_PVAS_INTF_ADDR_LIST lacks IPv6 address, cannot add multicast");
                    udp6.join(info.getAddress().getAddress(), info.getInterface());
                    logger.log(Level.FINE, "Listening to UDP multicast " + info);
                }
            }
        }

        if (local_multicast != null)
            logger.log(Level.FINE, "IPv4 unicasts are re-transmitted via local multicast " + local_multicast);

        if (udp4 != null)
        {
            final ByteBuffer receive_buffer = ByteBuffer.allocate(PVASettings.MAX_UDP_PACKET);
            listen_thread4 = new Thread(() -> listen(udp4, receive_buffer),
                                        "UDP4-receiver " + Network.getLocalAddress(udp4));
            listen_thread4.setDaemon(true);
            listen_thread4.start();
        }
        if (udp6 != null)
        {
            final ByteBuffer receive_buffer = ByteBuffer.allocate(PVASettings.MAX_UDP_PACKET);
            listen_thread6 = new Thread(() -> listen(udp6, receive_buffer),
                                        "UDP6-receiver " + Network.getLocalAddress(udp6));
            listen_thread6.setDaemon(true);
            listen_thread6.start();
        }
    }

    @Override
    protected boolean handleMessage(final InetSocketAddress from, final byte version,
                                    final byte command, final int payload, final ByteBuffer buffer)
    {
        switch (command)
        {
        case PVAHeader.CMD_ORIGIN_TAG:
            return handleOriginTag(from, version, payload, buffer);
        case PVAHeader.CMD_SEARCH:
            return handleSearch(from, version, payload, buffer);
        default:
            logger.log(Level.WARNING, "PVA Client " + from + " sent UDP packet with unknown command 0x" + Integer.toHexString(command));
        }
        return true;
    }

    private boolean handleOriginTag(final InetSocketAddress from, final byte version,
                                    final int payload, final ByteBuffer buffer)
    {
        final InetAddress addr;
        try
        {
            addr = PVAAddress.decode(buffer);
        }
        catch (Exception ex)
        {
            logger.log(Level.WARNING, "PVA Client " + from + " sent origin tag with invalid address");
            return false;
        }
        logger.log(Level.FINER, () -> "PVA Client " + from + " sent origin tag " + addr);

        return true;
    }

    /** @param from Origin of search request
     *  @param version Client's version
     *  @param payload Size of payload
     *  @param buffer Buffer with search request
     *  @return Valid request?
     */
    private boolean handleSearch(final InetSocketAddress from, final byte version,
                                 final int payload, final ByteBuffer buffer)
    {
        final SearchRequest search = SearchRequest.decode(from, version, payload, buffer);
        if (search == null)
            return false;

        if (search.name == null)
        {
            if (search.reply_required)
            {   // pvlist request
                search_handler.handleSearchRequest(0, -1, null, search.client);
                if (search.unicast)
                    PVAServer.POOL.submit(() -> forwardSearchRequest(0, -1, null, search.client));
            }
        }
        else
        {   // Channel search request
            for (int i=0; i<search.name.length; ++i)
            {
                final int cid = search.cid[i];
                final String name = search.name[i];
                search_handler.handleSearchRequest(search.seq, cid, name, search.client);
                if (search.unicast)
                    PVAServer.POOL.submit(() -> forwardSearchRequest(search.seq, cid, name, search.client));
            }
        }

        return true;
    }

    /** Forward a search request that we received as unicast to the local multicast group
     *
     *  <p>For example, when two servers run on the same host,
     *  only the one started last will see UDP search requests unicast from a remote client.
     *
     *  <p>This method forwards them to the local multicast group,
     *  allowing all servers on this host to reply.
     *
     *  @param seq Search sequence or 0
     *  @param cid Channel ID or -1
     *  @param name Name or <code>null</code>
     *  @param address Client's address and port
     */
    private void forwardSearchRequest(final int seq, final int cid, final String name, final InetSocketAddress address)
    {
        if (local_multicast == null)
            return;
        synchronized (send_buffer)
        {
            send_buffer.clear();
            SearchRequest.encode(false, seq, cid, name, address, send_buffer);
            send_buffer.flip();
            logger.log(Level.FINER, () -> "Forward search to " + local_multicast + "\n" + Hexdump.toHexdump(send_buffer));
            try
            {
                // TODO TTL? Interface?
                udp4.send(send_buffer, local_multicast.getAddress());
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot forward search", ex);
            }
        }
    }

    /** Send a "channel found" reply to a client's search
     *  @param guid This server's GUID
     *  @param seq Client search request sequence number
     *  @param cid Client's channel ID or -1
     *  @param tcp TCP connection where client can connect to this server
     *  @param client Address of client's UDP port
     */
    public void sendSearchReply(final Guid guid, final int seq, final int cid, final ServerTCPListener tcp, final InetSocketAddress client)
    {
        synchronized (send_buffer)
        {
            send_buffer.clear();
            PVAHeader.encodeMessageHeader(send_buffer, PVAHeader.FLAG_SERVER, PVAHeader.CMD_SEARCH_RESPONSE, 12+4+16+2+4+1+2+ (cid < 0 ? 0 : 4));

            // Server GUID
            guid.encode(send_buffer);

            // Search Sequence ID
            send_buffer.putInt(seq);

            // Server's address and port
            PVAAddress.encode(tcp.response_address, send_buffer);
            send_buffer.putShort((short)tcp.response_port);

            // Protocol
            PVAString.encodeString("tcp", send_buffer);

            // Found
            PVABool.encodeBoolean(cid >= 0, send_buffer);

            // int[] cid;
            if (cid < 0)
                send_buffer.putShort((short)0);
            else
            {
                send_buffer.putShort((short)1);
                send_buffer.putInt(cid);
            }

            send_buffer.flip();
// TODO            logger.log(Level.FINER, () ->
//            {
//                String port;
//                try
//                {
//                    port = Integer.toString(((InetSocketAddress) udp.getLocalAddress()).getPort());
//                }
//                catch (Exception ex)
//                {
//                    port = "unknown";
//                }
//                return "Sending search reply from port " + port + " to " + client + "\n" + Hexdump.toHexdump(send_buffer);
//            });

            try
            {
                // TODO TTL, interface
                if (client.getAddress() instanceof Inet4Address)
                    udp4.send(send_buffer, client);
                else
                    udp6.send(send_buffer, client);
            }
            catch (Exception ex)
            {
                logger.log(Level.WARNING, "Cannot send search reply", ex);
            }
        }
    }

    @Override
    public void close()
    {
        super.close();
        // Close sockets, wait a little for threads to exit
        try
        {
            if (udp4 != null)
                udp4.close();
            if (udp6 != null)
                udp6.close();

            if (listen_thread4 != null)
                listen_thread4.join(5000);
            if (listen_thread6 != null)
                listen_thread6.join(5000);
        }
        catch (Exception ex)
        {
            // Ignore
        }
    }
}