/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltcore.agreement;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.FaultMessage;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.SiteFailureForwardMessage;
import org.voltcore.messaging.SiteFailureMessage;
import org.voltcore.messaging.Subject;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.utils.CoreUtils;
import org.voltcore.utils.Pair;
import org.voltdb.VoltDB;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Longs;

public class MeshArbiter {

    protected static final VoltLogger m_recoveryLog = new VoltLogger("REJOIN");

    /**
     * During arbitration this map keys contain failed sites we are seeking
     * resolution for, and the values indicate whether or not the fault was
     * witnessed directly or relayed by others
     */
    protected final Map<Long, Boolean> m_inTrouble = Maps.newTreeMap();
    /**
     * The invoking agreement site hsid
     */
    protected final long m_hsId;
    protected final Mailbox m_mailbox;
    /**
     * Companion interface that aides in pinging, and getting safe site zookeeper
     * transaction ids
     */
    protected final MeshAide m_meshAide;
    /**
     * A map whree the keys describe graph links between alive sites and
     * sites listed in the {@link #m_inTrouble} map, and the values are
     * the safe zookeeper transaction ids reported by alive sites
     */
    protected final HashMap<Pair<Long, Long>, Long> m_failureSiteUpdateLedger =
            Maps.newHashMap();
    /**
     * Historic list of failed sites
     */
    protected final Set<Long> m_failedSites = Sets.newTreeSet();
    /**
     * in single link failures some sites in {@link #m_inTrouble} remain
     * alive after arbitration resolution. This set aides in the determination
     * of which incoming message are deemed stale
     */
    protected final Set<Long> m_staleUnwitnessed = Sets.newTreeSet();

    protected final Map<Long,SiteFailureForwardMessage> m_forwardCandidates = Maps.newHashMap();
    /**
     * it builds mesh graphs, and determines the the kill set to resolve
     * an arbitration
     */
    protected final AgreementSeeker m_seeker;

    /**
     * useful when probing the state of this mesh arbiter
     */
    protected volatile int m_inTroubleCount = 0;
    /**
     * useful when probing the state of this mesh arbiter. Each
     * resolved arbitration increments this counter
     */
    protected volatile int m_failedSitesCount = 0;

    public MeshArbiter(final long hsId, final Mailbox mailbox,
            final MeshAide meshAide) {

        m_hsId  = hsId;
        m_mailbox = mailbox;
        m_meshAide = meshAide;
        m_seeker = new AgreementSeeker(ArbitrationStrategy.MATCHING_CARDINALITY, m_hsId);
    }

    public boolean isInArbitration() {
        return m_inTroubleCount > 0;
    }

    public int getFailedSitesCount() {
        return m_failedSitesCount;
    }

    enum Discard {
        Suicide {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name());
            }
        },
        AlreadyFailed {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " "
                        + CoreUtils.hsIdToString(fm.failedSite));
            }
        },
        ReporterFailed {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " "
                        + CoreUtils.hsIdToString(fm.reportingSite));
            }
        },
        Unknown {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " "
                        + CoreUtils.hsIdToString(fm.failedSite));
            }
        },
        ReporterUnknown {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " "
                        + CoreUtils.hsIdToString(fm.reportingSite));
            }
        },
        SelfUnwitnessed {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " "
                        + CoreUtils.hsIdToString(fm.failedSite));
            }
        },
        AlreadyKnow {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " "
                        + CoreUtils.hsIdToString(fm.failedSite));
            }
        },
        StaleUnwinessed {
            @Override
            void log(FaultMessage fm) {
                m_recoveryLog.info("Agreement, Discarding " + name() + " " + fm);
            }
        },
        DoNot {
            @Override
            void log(FaultMessage fm) {
            }
        };

        abstract void log(FaultMessage fm);
    }

    protected Discard mayIgnore(Set<Long> hsIds, FaultMessage fm) {
        Boolean alreadyWitnessed = m_inTrouble.get(fm.failedSite);

        if (fm.failedSite == m_hsId) {
            return Discard.Suicide;
        } else if (m_failedSites.contains(fm.failedSite)) {
            return Discard.AlreadyFailed;
        } else if (m_failedSites.contains(fm.reportingSite)) {
            return Discard.ReporterFailed;
        } else if (!hsIds.contains(fm.failedSite)) {
            return Discard.Unknown;
        } else if (!hsIds.contains(fm.reportingSite)) {
            return Discard.ReporterUnknown;
        } else if (!fm.witnessed && fm.reportingSite == m_hsId) {
            return Discard.SelfUnwitnessed;
        } else if (   alreadyWitnessed != null
                   && (alreadyWitnessed || alreadyWitnessed == fm.witnessed)) {
            return Discard.AlreadyKnow;
        } else if (   !fm.witnessed
                    && m_inTrouble.isEmpty()
                    && m_staleUnwitnessed.contains(fm.failedSite)
                    && (   Sets.filter(fm.survivors, in(m_failedSites)).size() > 0
                        || fm.survivors.equals(m_seeker.getSurvivors()))) {
            return Discard.StaleUnwinessed;
        } else {
            return Discard.DoNot;
        }
    }

    /**
     * Process the fault message, and if necessary start arbitration.
     * @param hsIds pre-failure mesh ids
     * @param fm a {@link FaultMessage}
     * @return a map where the keys are the sites we need to disconnect from, and
     *   the values the last know safe zookeeper transaction ids for the sites
     *   we need to disconnect from. A map with entries indicate that an
     *   arbitration resolutions has been reached, while a map without entries
     *   indicate either a stale message, or that an agreement has not been
     *   reached
     */
    public Map<Long,Long> reconfigureOnFault(Set<Long> hsIds, FaultMessage fm) {
        final Subject [] justFailures = new Subject [] { Subject.FAILURE };
        boolean proceed = false;
        do {
            Discard ignoreIt = mayIgnore(hsIds,fm);
            if (Discard.DoNot == ignoreIt) {
                m_inTrouble.put(fm.failedSite,fm.witnessed);
                m_recoveryLog.info("Agreement, Processing " + fm);
                proceed = true;
            } else {
                ignoreIt.log(fm);
            }

            fm = (FaultMessage)m_mailbox.recv(justFailures);
        } while (fm != null);

        if (!proceed) {
            return ImmutableMap.of();
        }

        m_inTroubleCount = m_inTrouble.size();

        // we are here if failed site was not previously recorded
        // or it was previously recorded but it became witnessed from unwitnessed
        m_seeker.startSeekingFor(Sets.difference(hsIds, m_failedSites), m_inTrouble);

        discoverGlobalFaultData_send();

        if (discoverGlobalFaultData_rcv(hsIds)) {
            Map<Long,Long> lastTxnIdByFailedSite = extractGlobalFaultData(hsIds);
            if (lastTxnIdByFailedSite.isEmpty()) {
                return ImmutableMap.of();
            }

            notifyDanglers(lastTxnIdByFailedSite);

            m_failedSites.addAll( lastTxnIdByFailedSite.keySet());
            m_failedSitesCount = m_failedSites.size();

            m_recoveryLog.info(
                    "Agreement, Adding "
                  + CoreUtils.hsIdCollectionToString(lastTxnIdByFailedSite.keySet())
                  + " to failed sites history");

            clearInTrouble();
            m_seeker.clear();

            return lastTxnIdByFailedSite;
        } else {
            return ImmutableMap.of();
        }
    }

    /**
     * Notify all survivors when you are closing a link to nodes
     * you can still communicate to.
     * @param decision map where the keys contain the kill sites
     *   and its values are their last known safe transaction ids
     */
    protected void notifyDanglers(Map<Long,Long> decision) {
        Set<Long> danglers = Maps.filterValues(m_inTrouble, equalTo(Boolean.FALSE)).keySet();
        if (danglers.isEmpty()) return;

        SiteFailureMessage.Builder sfmb = SiteFailureMessage.builder();

        Set<Long> dests = Sets.filter(m_seeker.getSurvivors(),not(equalTo(m_hsId)));
        sfmb.addSurvivors(Sets.difference(m_seeker.getSurvivors(), decision.keySet()));

        for (Map.Entry<Long, Long> e: decision.entrySet()) {
            sfmb.addSafeTxnId(e.getKey(), e.getValue());
        }

        SiteFailureMessage sfm = sfmb.build();
        m_mailbox.send(Longs.toArray(dests), sfm);

        m_recoveryLog.info("Agreement, sending danglers ["
                + CoreUtils.hsIdCollectionToString(dests) + "]  " + sfm);
    }

    protected void clearInTrouble() {
        m_staleUnwitnessed.clear();
        m_forwardCandidates.clear();

        Iterator<Map.Entry<Pair<Long,Long>, Long>> litr =
                m_failureSiteUpdateLedger.entrySet().iterator();

        while (litr.hasNext()) {
            Map.Entry<Pair<Long,Long>, Long> e = litr.next();
            Long failedSite = e.getKey().getSecond();
            if (m_inTrouble.containsKey(failedSite)) {
                litr.remove();
            }
        }

        Iterator<Map.Entry<Long, Boolean>> itr = m_inTrouble.entrySet().iterator();

        while (itr.hasNext()) {
            Map.Entry<Long, Boolean> e = itr.next();
            if (!e.getValue() && !m_failedSites.contains(e.getKey())) {
                m_staleUnwitnessed.add(e.getKey());
            }
            itr.remove();
        }
        m_inTroubleCount = 0;
    }

    /**
     * Send one message to each surviving execution site providing this site's
     * multi-partition commit point and this site's safe txnid
     * (the receiver will filter the later for its
     * own partition). Do this once for each failed initiator that we know about.
     * Sends all data all the time to avoid a need for request/response.
     */
    private void discoverGlobalFaultData_send() {
        Set<Long> dests = m_seeker.getSurvivors();

        m_recoveryLog.info("Agreement, Sending survivor set "
                + CoreUtils.hsIdCollectionToString(m_seeker.getSurvivors()));

        SiteFailureMessage.Builder msgBuilder = SiteFailureMessage.builder();
        msgBuilder.addSurvivors(m_seeker.getSurvivors());
        for (long troubled: m_inTrouble.keySet()) {
            if (troubled == m_hsId) continue;
            /*
             * Check the queue for the data and get it from the ledger if necessary.\
             * It might not even be in the ledger if the site has been failed
             * since recovery of this node began.
             */
            Long txnId = m_meshAide.getNewestSafeTransactionForInitiator(troubled);
            msgBuilder.addSafeTxnId(troubled, txnId != null ? txnId : Long.MIN_VALUE);
        }

        m_mailbox.send(Longs.toArray(dests), msgBuilder.build());
    }

    protected void updateFailedSitesLedger(Set<Long> hsIds,SiteFailureMessage sfm) {
        for (Map.Entry<Long, Long> e: sfm.m_safeTxnIds.entrySet()) {

            if(!hsIds.contains(e.getKey()) || m_hsId == e.getKey()) continue;

            m_failureSiteUpdateLedger.put(
                    Pair.of(sfm.m_sourceHSId, e.getKey()),
                    e.getValue());
        }
    }

    protected void updateFailedSiteLedger(Set<Long> hsIds) {
        for (Long site: m_inTrouble.keySet()) {
            if (site == m_hsId) continue;
            long txnid = m_meshAide.getNewestSafeTransactionForInitiator(site);
            m_failureSiteUpdateLedger.put(Pair.of(m_hsId, site), txnid);
        }
    }

    /**
     * Collect the failure site update messages from all sites This site sent
     * its own mailbox the above broadcast the maximum is local to this site.
     * This also ensures at least one response.
     *
     * Concurrent failures can be detected by additional reports from the FaultDistributor
     * or a mismatch in the set of failed hosts reported in a message from another site
     */
    private boolean discoverGlobalFaultData_rcv(Set<Long> hsIds) {

        long blockedOnReceiveStart = System.currentTimeMillis();
        long lastReportTime = 0;
        final Subject [] receiveSubjects = new Subject [] {
                Subject.FAILURE,
                Subject.SITE_FAILURE_UPDATE,
                Subject.SITE_FAILURE_FORWARD
        };
        boolean haveEnough = false;

        do {
            VoltMessage m = m_mailbox.recvBlocking(receiveSubjects, 5);

            /*
             * If fault resolution takes longer then 10 seconds start logging
             */
            final long now = System.currentTimeMillis();
            if (now - blockedOnReceiveStart > 10000) {
                if (now - lastReportTime > 60000) {
                    lastReportTime = System.currentTimeMillis();
                    haveNecessaryFaultInfo(m_inTrouble.keySet(), true);
                }
            }

            if (m == null) {
                // Send a heartbeat to keep the dead host timeout active.  Needed because IV2 doesn't
                // generate its own heartbeats to keep this running.
                m_meshAide.sendHeartbeats(hsIds);
                continue;
            }

            if (m.getSubject() == Subject.SITE_FAILURE_UPDATE.getId()) {
                if (  !hsIds.contains(m.m_sourceHSId)
                    || m_failedSites.contains(m.m_sourceHSId)) continue;

                SiteFailureMessage sfm = (SiteFailureMessage)m;

                updateFailedSitesLedger(hsIds, sfm);

                m_seeker.add(sfm);
                m_forwardCandidates.put(sfm.m_sourceHSId, new SiteFailureForwardMessage(sfm));

                m_recoveryLog.info("Agreement, Received " + sfm);

            } else if (m.getSubject() == Subject.SITE_FAILURE_FORWARD.getId()) {
                SiteFailureForwardMessage fsfm = (SiteFailureForwardMessage)m;

                m_forwardCandidates.put(fsfm.m_reportingHSId, fsfm);

                if (   !hsIds.contains(fsfm.m_sourceHSId)
                    || m_seeker.getSurvivors().contains(fsfm.m_reportingHSId)
                    || m_failedSites.contains(fsfm.m_reportingHSId)) continue;

                m_seeker.add(fsfm);

                m_recoveryLog.info("Agreement, Received forward " + fsfm);

            } else if (m.getSubject() == Subject.FAILURE.getId()) {
                /*
                 * If the fault distributor reports a new fault, ignore it if it is known , otherwise
                 * re-deliver the message to ourself and then abort so that the process can restart.
                 */
                FaultMessage fm = (FaultMessage)m;

                Discard ignoreIt = mayIgnore(hsIds,fm);
                if (Discard.DoNot == ignoreIt) {
                    m_mailbox.deliverFront(m);
                    m_recoveryLog.info("Agreement, Detected a concurrent failure from FaultDistributor, new failed site "
                            + CoreUtils.hsIdToString(fm.failedSite));
                    return false;
                } else {
                    ignoreIt.log(fm);
                }
            }

            haveEnough = haveEnough || haveNecessaryFaultInfo(m_seeker.getSurvivors(), false);
            if (haveEnough) {

                Iterator<Map.Entry<Long, SiteFailureForwardMessage>> itr =
                        m_forwardCandidates.entrySet().iterator();

                while (itr.hasNext()) {
                    Map.Entry<Long, SiteFailureForwardMessage> e = itr.next();
                    Set<Long> unseenBy = m_seeker.forWhomSiteIsDead(e.getKey());
                    if (unseenBy.size() > 0) {
                        m_recoveryLog.info("Agreement, fowarding to "
                                + CoreUtils.hsIdCollectionToString(unseenBy)
                                + " " + e.getValue());
                        m_mailbox.send(Longs.toArray(unseenBy),e.getValue());
                    }
                    itr.remove();
                }
            }

        } while (!haveEnough || m_seeker.needForward());

        return true;
    }

    private boolean haveNecessaryFaultInfo( Set<Long> survivors, boolean log) {
        List<Pair<Long, Long>> missingMessages = new ArrayList<Pair<Long, Long>>();
        for (long survivingSite : survivors) {
            for (Long failingSite : m_inTrouble.keySet()) {
                Pair<Long, Long> key = Pair.of( survivingSite, failingSite);
                if (   survivingSite != failingSite
                    && !m_failureSiteUpdateLedger.containsKey(key)) {
                    missingMessages.add(key);
                }
            }
        }
        if (log) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Pair<Long, Long> p : missingMessages) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(CoreUtils.hsIdToString(p.getFirst()));
                sb.append('-');
                sb.append(CoreUtils.hsIdToString(p.getSecond()));
            }
            sb.append(']');
            if (missingMessages.isEmpty()) {
                sb.append(" ");
                sb.append(m_seeker.dumpAlive());
            }

            m_recoveryLog.warn("Failure resolution stalled waiting for ( ExecutionSite, Initiator ) " +
                                "information: " + sb.toString());
        }
        return missingMessages.isEmpty();
    }

    private Map<Long,Long> extractGlobalFaultData(Set<Long> hsIds) {

        if (!haveNecessaryFaultInfo(m_seeker.getSurvivors(), false)) {
            VoltDB.crashLocalVoltDB("Error extracting fault data", true, null);
        }

        Set<Long> toBeKilled = m_seeker.nextKill();
        Map<Long, Long> initiatorSafeInitPoint = new HashMap<Long, Long>();

        Iterator<Map.Entry<Pair<Long, Long>, Long>> iter =
            m_failureSiteUpdateLedger.entrySet().iterator();

        while (iter.hasNext()) {
            final Map.Entry<Pair<Long, Long>, Long> entry = iter.next();
            final Pair<Long, Long> key = entry.getKey();
            final Long safeTxnId = entry.getValue();

            if (!hsIds.contains(key.getFirst()) || !toBeKilled.contains(key.getSecond())) {
                continue;
            }

            Long initiatorId = key.getSecond();
            if (!initiatorSafeInitPoint.containsKey(initiatorId)) {
                initiatorSafeInitPoint.put( initiatorId, Long.MIN_VALUE);
            }

            initiatorSafeInitPoint.put( initiatorId,
                    Math.max(initiatorSafeInitPoint.get(initiatorId), safeTxnId));
        }
        assert(!initiatorSafeInitPoint.containsValue(Long.MIN_VALUE));

        initiatorSafeInitPoint.remove(m_hsId);

        return ImmutableMap.copyOf(initiatorSafeInitPoint);
    }

}
