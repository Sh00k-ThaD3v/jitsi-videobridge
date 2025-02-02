/*
 * Copyright @ 2019-Present 8x8, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.shim;

import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

public class ColibriUtil
{
    private static final Logger logger = new LoggerImpl(ColibriUtil.class.getName());

    /**
     * This method collects all of the channel bundle IDs referenced in the given IQ.
     * @param conferenceIq
     * @return
     */
    static Set<String> getAllSignaledChannelBundleIds(
            ColibriConferenceIQ conferenceIq)
    {
        Set<String> channelBundleIds = new HashSet<>();
        for (ColibriConferenceIQ.Content contentIq : conferenceIq.getContents())
        {
            for (ColibriConferenceIQ.Channel channelIq : contentIq.getChannels())
            {
                channelBundleIds.add(channelIq.getChannelBundleId());
            }
            for (ColibriConferenceIQ.SctpConnection sctpConnIq : contentIq.getSctpConnections())
            {
                channelBundleIds.add(sctpConnIq.getChannelBundleId());
            }
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq : conferenceIq.getChannelBundles())
        {
            channelBundleIds.add(channelBundleIq.getId());
        }
        return channelBundleIds;
    }

    /**
     * Checks if a {@link ColibriConferenceIQ.Channel} refers to an Octo
     * channel.
     *
     * Some requests do not explicitly set the 'type' attribute to 'octo',
     * resulting in the element being parsed as a regular
     * {@link ColibriConferenceIQ.Channel}. To cover this case we check if the
     * ID is one of the IDs we use for Octo.
     *
     * @param channel the channel to check
     * @return {@code true} if the Channel is an Octo channel.
     */
    private static boolean isOctoChannel(ColibriConferenceIQ.Channel channel)
    {
        if (channel instanceof ColibriConferenceIQ.OctoChannel)
        {
            return true;
        }

        if (channel != null)
        {
            String id = channel.getID();
            return id != null &&
                    (id.equals(getOctoChannelId(MediaType.AUDIO))
                    || id.equals(getOctoChannelId(MediaType.VIDEO)));
        }

        return false;
    }

    /**
     * Gets the channel ID we will use for the Octo channel with a given media type.
     * @param mediaType
     * @return
     */
    static String getOctoChannelId(MediaType mediaType)
    {
        return "octo-" + mediaType;
    }

    /**
     * Processes a list of {@code Channel} elements in a specific
     * {@link ContentShim}.
     *
     * @param channelIqs the list of channel elements.
     * @param contentShim the associated {@code ContentShim}
     * @return the list of channel elements that have been created and updated
     * (but haven't been expired).
     * @throws IqProcessingException
     */
    static List<ColibriConferenceIQ.Channel> processChannels(
            List<ColibriConferenceIQ.Channel> channelIqs,
            ContentShim contentShim)
            throws IqProcessingException
    {
        List<ColibriConferenceIQ.Channel> createdOrUpdatedChannels = new ArrayList<>();

        for (ColibriConferenceIQ.Channel channelIq : channelIqs)
        {
            // Octo channels are handled separately.
            if (isOctoChannel(channelIq))
            {
                continue;
            }

            ChannelShim channelShim = contentShim.getOrCreateChannelShim(channelIq);
            if (channelShim == null)
            {
                // A channel expire request which was handled successfully.
                continue;
            }


            channelShim.setDirection(channelIq.getDirection());
            channelShim.addPayloadTypes(channelIq.getPayloadTypes());
            channelShim.addRtpHeaderExtensions(channelIq.getRtpHeaderExtensions());

            List<SourcePacketExtension> channelSources = channelIq.getSources();
            channelShim.setSources(channelSources);
            channelShim.setSourceGroups(channelIq.getSourceGroups());

            // We only create tracks for video right now, because we don't have
            // audio tracks. So only trigger re-creation of the tracks when a
            // video channel is signaled.
            if (MediaType.VIDEO.equals(contentShim.getMediaType()) && !channelSources.isEmpty())
            {
                EndpointShim endpointShim = channelShim.getEndpoint().getEndpointShim();
                if (endpointShim == null)
                {
                    throw new IqProcessingException(StanzaError.Condition.internal_server_error, "No endpointShim");
                }

                List<SourcePacketExtension> sources = new ArrayList<>();
                List<SourceGroupPacketExtension> sourceGroups = new ArrayList<>();
                endpointShim.getVideoChannels().forEach(channel ->
                {
                    Collection<SourcePacketExtension> s = channel.getSources();
                    if (s != null)
                    {
                        sources.addAll(s);
                    }

                    Collection<SourceGroupPacketExtension> sg = channel.getSourceGroups();
                    if (sg != null)
                    {
                        sourceGroups.addAll(sg);
                    }
                });

                if (!sources.isEmpty() || !sourceGroups.isEmpty())
                {
                    channelShim.getEndpoint().setMediaSources(
                            MediaSourceFactory.createMediaSources(sources, sourceGroups));
                }
            }

            Integer channelLastN = channelIq.getLastN();
            if (channelLastN != null)
            {
                channelShim.setLastN(channelLastN);
            }
            ColibriConferenceIQ.Channel responseChannelIQ = new ColibriConferenceIQ.Channel();
            channelShim.describe(responseChannelIQ);
            createdOrUpdatedChannels.add(responseChannelIQ);

            if (channelIq.getTransport() != null)
            {
                String message =
                        "Received a COLIBRI request with 'transport' inside " +
                        "'channel'. This legacy mode is no longer supported";
                logger.warn(message);
                throw new IqProcessingException(StanzaError.Condition.bad_request, message);
            }
        }

        return createdOrUpdatedChannels;
    }

    /**
     * Processes the list of {@link ColibriConferenceIQ.SctpConnection}s
     * present in a received {@link ColibriConferenceIQ}.  Returns a list of
     * {@link ColibriConferenceIQ.SctpConnection} elements that contain
     * descriptions of the created and/or updated SCTP connection instances.
     * @param sctpConnections
     * @param contentShim
     * @return
     * @throws IqProcessingException if there are any errors during the
     * processing of the incoming connections.
     */
    static List<ColibriConferenceIQ.SctpConnection> processSctpConnections(
            List<ColibriConferenceIQ.SctpConnection> sctpConnections,
            ContentShim contentShim) throws IqProcessingException
    {
        List<ColibriConferenceIQ.SctpConnection> createdOrUpdatedSctpConnections = new ArrayList<>();
        for (ColibriConferenceIQ.SctpConnection sctpConnIq : sctpConnections)
        {
            if (!SctpConfig.config.enabled())
            {
                throw new IqProcessingException(
                        StanzaError.Condition.feature_not_implemented,
                        "SCTP support is not configured");
            }
            SctpConnectionShim sctpConnectionShim = contentShim.getOrCreateSctpConnectionShim(sctpConnIq);
            if (sctpConnectionShim == null)
            {
                // A channel expire request which was handled successfully.
                continue;
            }

            ColibriConferenceIQ.SctpConnection responseSctpIq = new ColibriConferenceIQ.SctpConnection();

            sctpConnectionShim.describe(responseSctpIq);

            createdOrUpdatedSctpConnections.add(responseSctpIq);
        }

        return createdOrUpdatedSctpConnections;
    }

    /**
     * Gets the first {@code OctoChannel} in the given content, or null.
     */
    static ColibriConferenceIQ.Channel findOctoChannel(ColibriConferenceIQ.Content content)
    {
        return content.getChannels().stream().filter(ColibriUtil::isOctoChannel) .findAny().orElse(null);
    }

    private ColibriUtil() {}
}
