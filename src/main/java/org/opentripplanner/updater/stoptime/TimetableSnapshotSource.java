/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.updater.stoptime;

 
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TimeZone;
 



import java.util.TreeSet;

import com.google.common.collect.Maps;

import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.opentripplanner.routing.core.ServiceDay;
import org.opentripplanner.routing.edgetype.Timetable;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.edgetype.TimetableResolver;
import org.opentripplanner.routing.edgetype.TimetableResolver.SortedTimetableComparator;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.GraphIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;

/**
 * This class should be used to create snapshots of lookup tables of realtime data. This is
 * necessary to provide planning threads a consistent constant view of a graph with realtime data at
 * a specific point in time.
 */
public class TimetableSnapshotSource {
    private static final Logger LOG = LoggerFactory.getLogger(TimetableSnapshotSource.class);

    public int logFrequency = 2000;

    private int appliedBlockCount = 0;

    /**
     * If a timetable snapshot is requested less than this number of milliseconds after the previous
     * snapshot, just return the same one. Throttles the potentially resource-consuming task of
     * duplicating a TripPattern -> Timetable map and indexing the new Timetables.
     */
    public int maxSnapshotFrequency = 1000; // msec

    /**
     * The last committed snapshot that was handed off to a routing thread. This snapshot may be
     * given to more than one routing thread if the maximum snapshot frequency is exceeded.
     */
    private TimetableResolver snapshot = null;

    /** The working copy of the timetable resolver. Should not be visible to routing threads. */
    private TimetableResolver buffer = new TimetableResolver();

    /** Should expired realtime data be purged from the graph. */
    public boolean purgeExpiredData = true;

    protected ServiceDate lastPurgeDate = null;

    protected long lastSnapshotTime = -1;

    private final TimeZone timeZone;

    private GraphIndex graphIndex;
    //just for test
    FileWriter logFile;
    public TimetableSnapshotSource(Graph graph) {
        timeZone = graph.getTimeZone();
        graphIndex = graph.index;
 
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
 
		try {
			logFile = new FileWriter("tripUpdatesLogs.txt", false);
			logFile.write("      OTP started at "+ dateFormat.format(date));
			logFile.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
 
    }

    /**
     * @return an up-to-date snapshot mapping TripPatterns to Timetables. This snapshot and the
     *         timetable objects it references are guaranteed to never change, so the requesting
     *         thread is provided a consistent view of all TripTimes. The routing thread need only
     *         release its reference to the snapshot to release resources.
     */
    public TimetableResolver getTimetableSnapshot() {
        return getTimetableSnapshot(false);
    }

    protected synchronized TimetableResolver getTimetableSnapshot(boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastSnapshotTime > maxSnapshotFrequency) {
            if (force || buffer.isDirty()) {
                LOG.debug("Committing {}", buffer.toString());
                snapshot = buffer.commit(force);
            } else {
                LOG.debug("Buffer was unchanged, keeping old snapshot.");
            }
            lastSnapshotTime = System.currentTimeMillis();
        } else {
            LOG.debug("Snapshot frequency exceeded. Reusing snapshot {}", snapshot);
        }
        return snapshot;
    }

    /**
     * Method to apply a trip update list to the most recent version of the timetable snapshot.
     * A GTFS-RT feed is always applied against a single static feed (indicated by feedId).
     * However, multi-feed support is not completed and we currently assume there is only one static feed when matching IDs.
     */
    public void applyTripUpdates(List<TripUpdate> updates, String feedId) {
        if (updates == null) {
            LOG.warn("updates is null");
            return;
        }

        // After every real-time update, the trip times are deleted and reset.
        // TODO: Handle ScheduleRelationship = NO_DATA values so that GTFS-rt producers
        //        can communicate that there is a bus running, but they don't have any realtime info about it
        //       see https://github.com/opentripplanner/OpenTripPlanner/issues/1347#issuecomment-45012120.
        for (TripPattern pattern : graphIndex.patternForTrip.values()) {

            // check if the pattern belongs to frequencyBased trips
        	if (pattern.scheduledTimetable.frequencyEntries.size() != 0) {
                SortedSet<Timetable> sortedTimetables = buffer.timetables.get(pattern);
                if (sortedTimetables != null) {

                    for (Timetable timetable : sortedTimetables) {
                        int currentSize = timetable.tripTimes.size();
                        for (int i = 0; i < pattern.noTrips; i++) {
                            timetable.getTripTimes(i).vehicleID = null;
                        }
                        for (int i = currentSize - 1; pattern.noTrips <= i; i--) {
                        	timetable.tripTimes.remove(i);
                        }
                    }
                }
            }
        }

        LOG.debug("message contains {} trip updates", updates.size());
        int uIndex = 0;
        for (TripUpdate tripUpdate : updates) {
            if (!tripUpdate.hasTrip()) {
                LOG.warn("Missing TripDescriptor in gtfs-rt trip update: \n{}", tripUpdate);
                continue;
            }

            ServiceDate serviceDate = new ServiceDate();
            TripDescriptor tripDescriptor = tripUpdate.getTrip();

            if (tripDescriptor.hasStartDate()) {
                try {
                    serviceDate = ServiceDate.parseString(tripDescriptor.getStartDate());
                } catch (ParseException e) {
                    LOG.warn("Failed to parse startDate in gtfs-rt trip update: \n{}", tripUpdate);
                    continue;
                }
            }

            uIndex += 1;
            LOG.debug("trip update #{} ({} updates) :",
                    uIndex, tripUpdate.getStopTimeUpdateCount());
            LOG.trace("{}", tripUpdate);

            boolean applied = false;
            if (tripDescriptor.hasScheduleRelationship()) {
                switch(tripDescriptor.getScheduleRelationship()) {
                    case SCHEDULED:
                        applied = handleScheduledTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case ADDED:
                        applied = handleAddedTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case UNSCHEDULED:
                        applied = handleUnscheduledTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case CANCELED:
                        applied = handleCanceledTrip(tripUpdate, feedId, serviceDate);
                        break;
                    case REPLACEMENT:
                        applied = handleReplacementTrip(tripUpdate, feedId, serviceDate);
                        break;
                }
            } else {
                // Default
                applied = handleScheduledTrip(tripUpdate, feedId, serviceDate);
            }

            if(applied) {
                appliedBlockCount++;
             } else {
                 LOG.warn("Failed to apply TripUpdate.");
                 LOG.trace(" Contents: {}", tripUpdate);
             }

             if (appliedBlockCount % logFrequency == 0) {
                 LOG.info("Applied {} trip updates.", appliedBlockCount);
             }
        }
        LOG.debug("end of update message");

        // Make a snapshot after each message in anticipation of incoming requests
        // Purge data if necessary (and force new snapshot if anything was purged)
        if(purgeExpiredData) {
            boolean modified = purgeExpiredData();
            getTimetableSnapshot(modified);
        } else {
            getTimetableSnapshot();
        }
         
    }

    protected boolean handleScheduledTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        TripDescriptor tripDescriptor = tripUpdate.getTrip();
        // This does not include Agency ID or feed ID, trips are feed-unique and we currently assume a single static feed.
        String tripId = tripDescriptor.getTripId();
        TripPattern pattern = getPatternForTripId(tripId);

        if (pattern == null) {
            LOG.warn("No pattern found for tripId {}, skipping TripUpdate.", tripId);
            return false;
        }

        if (tripUpdate.getStopTimeUpdateCount() < 1) {
            LOG.warn("TripUpdate contains no updates, skipping.");
            return false;
        }
      
        // we have a message we actually want to apply
        return buffer.update(pattern, tripUpdate, feedId, timeZone, serviceDate);
    }

    protected boolean handleAddedTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        // TODO: Handle added trip
        LOG.warn("Added trips are currently unsupported. Skipping TripUpdate.");
        return false;
    }

    protected boolean handleUnscheduledTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        // TODO: Handle unscheduled trip
        LOG.warn("Unscheduled trips are currently unsupported. Skipping TripUpdate.");
        return false;
    }

    protected boolean handleReplacementTrip(TripUpdate tripUpdate, String feedId, ServiceDate serviceDate) {
        // TODO: Handle replacement trip
        LOG.warn("Replacement trips are currently unsupported. Skipping TripUpdate.");
        return false;
    }

    protected boolean handleCanceledTrip(TripUpdate tripUpdate, String agencyId,
                                         ServiceDate serviceDate) {
        TripDescriptor tripDescriptor = tripUpdate.getTrip();
        String tripId = tripDescriptor.getTripId(); // This does not include Agency ID, trips are feed-unique.
        TripPattern pattern = getPatternForTripId(tripId);

        if (pattern == null) {
            LOG.warn("No pattern found for tripId {}, skipping TripUpdate.", tripId);
            return false;
        }

        return buffer.update(pattern, tripUpdate, agencyId, timeZone, serviceDate);
    }

    protected boolean purgeExpiredData() {
        ServiceDate today = new ServiceDate();
        ServiceDate previously = today.previous().previous(); // Just to be safe...

        if(lastPurgeDate != null && lastPurgeDate.compareTo(previously) > 0) {
            return false;
        }

        LOG.debug("purging expired realtime data");
        // TODO: purge expired realtime data

        lastPurgeDate = previously;

        return buffer.purgeExpiredData(previously);
    }

    protected TripPattern getPatternForTripId(String tripIdWithoutAgency) {
        /* Lazy-initialize a separate index that ignores agency IDs.
         * Stopgap measure assuming no cross-feed ID conflicts, until we get GTFS loader replaced. */
        if (graphIndex.tripForIdWithoutAgency == null) {
            Map<String, Trip> map = Maps.newHashMap();
            for (Trip trip : graphIndex.tripForId.values()) {
                map.put(trip.getId().getId(), trip);
            }
            graphIndex.tripForIdWithoutAgency = map;
        }
        Trip trip = graphIndex.tripForIdWithoutAgency.get(tripIdWithoutAgency);
        TripPattern pattern = graphIndex.patternForTrip.get(trip);
        return pattern;
    }

}
