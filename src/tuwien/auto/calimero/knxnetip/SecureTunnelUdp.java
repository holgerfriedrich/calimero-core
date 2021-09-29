/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2018, 2021 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.knxnetip;

import static tuwien.auto.calimero.knxnetip.Net.hostPort;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.knxnetip.servicetype.KNXnetIPHeader;
import tuwien.auto.calimero.knxnetip.util.CRI;
import tuwien.auto.calimero.knxnetip.util.TunnelCRI;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogService.LogLevel;
import tuwien.auto.calimero.secure.KnxSecureException;

final class SecureTunnelUdp extends KNXnetIPTunnel {
	private final SecureSessionUdp udp;

	SecureTunnelUdp(final TunnelingLayer knxLayer, final InetSocketAddress localEP,
			final InetSocketAddress serverCtrlEP, final boolean useNAT, final IndividualAddress tunnelingAddress,
			final SecureSessionUdp udp) throws KNXException, InterruptedException {
		super(knxLayer);
		this.udp = udp;

		final var cri = tunnelingAddress.equals(KNXMediumSettings.BackboneRouter) ? new TunnelCRI(knxLayer)
				: new TunnelCRI(knxLayer, tunnelingAddress);
		connect(localEP, serverCtrlEP, cri, useNAT);
	}

	@Override
	protected void connect(final InetSocketAddress localEP, final InetSocketAddress serverCtrlEP, final CRI cri,
			final boolean useNAT) throws KNXException, InterruptedException {
		// logger is required by receiver loop during session setup
		ctrlEndpt = serverCtrlEP;
		logger = LogService.getLogger("calimero.knxnetip." + name());

		final var local = Net.matchRemoteEndpoint(localEP, serverCtrlEP, useNAT);
		udp.setupSecureSession(this, local, serverCtrlEP, useNAT);

		super.connect(local, serverCtrlEP, cri, useNAT);
	}

	@Override
	public String name() { return "KNX/IP " + SecureConnection.secureSymbol + " Tunneling " + hostPort(ctrlEndpt); }

	@Override
	protected void send(final byte[] packet, final InetSocketAddress dst) throws IOException {
		final byte[] wrapped = udp.newSecurePacket(packet);
		super.send(wrapped, dst);
	}

	@Override
	protected boolean handleServiceType(final KNXnetIPHeader h, final byte[] data, final int offset,
			final InetAddress src, final int port) throws KNXFormatException, IOException {

		final int svc = h.getServiceType();
		if (!h.isSecure()) {
			logger.trace("received insecure service type 0x{} - ignore", Integer.toHexString(svc));
			return true;
		}

		if (svc == SecureConnection.SecureSessionResponse) {
			final var source = new InetSocketAddress(src, port);
			udp.sessionAuth(h, data, offset, source);
		}
		else if (svc == SecureConnection.SecureSvc) {
			final Object[] fields = udp.unwrap(h, data, offset);
			final byte[] packet = (byte[]) fields[4];
			final KNXnetIPHeader containedHeader = new KNXnetIPHeader(packet, 0);

			if (containedHeader.getServiceType() == SecureConnection.SecureSessionStatus) {
				final int status = TcpConnection.SecureSession.newChannelStatus(containedHeader, packet,
						containedHeader.getStructLength());
				LogService.log(logger, status == 0 ? LogLevel.TRACE : LogLevel.ERROR, "{}", udp.session());
				udp.quitSetupLoop();
				udp.sessionStatus = status;
				if (status != 0) // XXX do we need this throw, its swallowed by the loop anyway?
					throw new KnxSecureException("secure session " + SecureConnection.statusMsg(status));
			}
			else {
				// let base class handle decrypted knxip packet
				return super.handleServiceType(containedHeader, packet, containedHeader.getStructLength(), src, port);
			}
		}
		else
			logger.warn("received unsupported secure service type 0x{} - ignore", Integer.toHexString(svc));
		return true;
	}
}
