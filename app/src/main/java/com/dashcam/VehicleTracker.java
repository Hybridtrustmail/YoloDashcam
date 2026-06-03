package com.dashcam;

import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Enhanced vehicle tracker with parked car filtering
 */
public class VehicleTracker {
    private static final String TAG = "VehicleTracker";
    
    // Tracking parameters
    private static final float IOU_THRESHOLD = 0.3f;
    protected static final int MAX_FRAMES_TO_SKIP = 5;
    protected static final int MIN_FRAMES_FOR_TRACKING = 2;
    protected static final int PARKED_CAR_THRESHOLD = 30; // seconds
    protected static final float MOVEMENT_THRESHOLD = 5.0f; // pixels
    
    private Map<Integer, TrackedVehicle> tracks = new HashMap<>();
    private Map<Detection, Integer> detectionToTrackId = new HashMap<>();
    private int nextTrackId = 1;
    
    public Map<Integer, TrackedVehicle> updateTracks(List<Detection> detections) {
        detectionToTrackId.clear();
        
        // Update existing tracks
        for (TrackedVehicle track : tracks.values()) {
            track.incrementAge();
        }
        
        // Match detections to existing tracks
        List<Detection> unmatchedDetections = new ArrayList<>(detections);
        Set<Integer> matchedTrackIds = new HashSet<>();
        
        for (Detection detection : detections) {
            TrackedVehicle bestMatch = null;
            float bestIou = IOU_THRESHOLD;
            
            for (TrackedVehicle track : tracks.values()) {
                if (!track.isMatchable()) continue;
                if (matchedTrackIds.contains(track.trackId)) continue;
                if (track.getLastDetection() != null
                        && track.getLastDetection().classId != detection.classId) {
                    continue;
                }
                
                float iou = calculateIoU(detection.boundingBox, track.getLastBox());
                if (iou > bestIou) {
                    bestIou = iou;
                    bestMatch = track;
                }
            }
            
            if (bestMatch != null) {
                bestMatch.update(detection);
                detectionToTrackId.put(detection, bestMatch.trackId);
                matchedTrackIds.add(bestMatch.trackId);
                unmatchedDetections.remove(detection);
            }
        }
        
        // Create new tracks for unmatched detections
        for (Detection detection : unmatchedDetections) {
            TrackedVehicle newTrack = new TrackedVehicle(nextTrackId++, detection);
            tracks.put(newTrack.trackId, newTrack);
            detectionToTrackId.put(detection, newTrack.trackId);
        }
        
        // Remove old tracks and identify parked cars
        Iterator<Map.Entry<Integer, TrackedVehicle>> iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TrackedVehicle> entry = iterator.next();
            TrackedVehicle track = entry.getValue();
            
            if (track.framesSinceUpdate > MAX_FRAMES_TO_SKIP) {
                iterator.remove();
            } else if (track.isParked()) {
                // Log parked car but keep tracking
                Log.d(TAG, "Vehicle " + track.trackId + " appears to be parked");
            }
        }
        
        // Return only moving vehicles
        Map<Integer, TrackedVehicle> movingVehicles = new HashMap<>();
        for (Map.Entry<Integer, TrackedVehicle> entry : tracks.entrySet()) {
            if (!entry.getValue().isParked() && entry.getValue().isConfirmed()) {
                movingVehicles.put(entry.getKey(), entry.getValue());
            }
        }
        
        return movingVehicles;
    }
    
    public Integer getTrackId(Detection detection) {
        return detectionToTrackId.get(detection);
    }
    
    private float calculateIoU(RectF box1, RectF box2) {
        float x1 = Math.max(box1.left, box2.left);
        float y1 = Math.max(box1.top, box2.top);
        float x2 = Math.min(box1.right, box2.right);
        float y2 = Math.min(box1.bottom, box2.bottom);
        
        float intersectionArea = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float box1Area = (box1.right - box1.left) * (box1.bottom - box1.top);
        float box2Area = (box2.right - box2.left) * (box2.bottom - box2.top);
        float unionArea = box1Area + box2Area - intersectionArea;
        
        return intersectionArea / unionArea;
    }
    
    public int getActiveTrackCount() {
        int count = 0;
        for (TrackedVehicle track : tracks.values()) {
            if (track.isActive() && !track.isParked()) {
                count++;
            }
        }
        return count;
    }

    public void reset() {
        tracks.clear();
        detectionToTrackId.clear();
        nextTrackId = 1;
    }
}

/**
 * Enhanced tracked vehicle with parked car detection
 */
class TrackedVehicle {
    public final int trackId;
    public final List<Detection> detections = new ArrayList<>();
    public final List<RectF> positions = new ArrayList<>();
    public final List<Long> timestamps = new ArrayList<>();
    
    public int framesSinceUpdate = 0;
    public int totalFrames = 0;
    
    // Movement tracking
    private float totalMovement = 0;
    private RectF firstPosition;
    private long firstTimestamp;
    
    public TrackedVehicle(int trackId, Detection detection) {
        this.trackId = trackId;
        this.firstPosition = new RectF(detection.boundingBox);
        this.firstTimestamp = detection.timestamp;
        update(detection);
    }
    
    public void update(Detection detection) {
        detections.add(detection);
        positions.add(new RectF(detection.boundingBox));
        timestamps.add(detection.timestamp);
        
        framesSinceUpdate = 0;
        totalFrames++;
        
        // Calculate movement
        if (positions.size() > 1) {
            RectF lastPos = positions.get(positions.size() - 2);
            RectF currentPos = detection.boundingBox;
            
            float dx = currentPos.centerX() - lastPos.centerX();
            float dy = currentPos.centerY() - lastPos.centerY();
            float movement = (float) Math.sqrt(dx * dx + dy * dy);
            
            totalMovement += movement;
        }
    }
    
    public void incrementAge() {
        framesSinceUpdate++;
    }
    
    public boolean isActive() {
        return framesSinceUpdate == 0;
    }

    public boolean isMatchable() {
        return framesSinceUpdate <= VehicleTracker.MAX_FRAMES_TO_SKIP;
    }
    
    public boolean isConfirmed() {
        return totalFrames >= VehicleTracker.MIN_FRAMES_FOR_TRACKING;
    }
    
    public boolean isParked() {
        if (timestamps.size() < 2) return false;
        
        long duration = timestamps.get(timestamps.size() - 1) - firstTimestamp;
        float avgMovementPerFrame = totalMovement / Math.max(1, positions.size() - 1);
        
        // Consider parked if: long duration, little movement, and still being detected
        return duration > VehicleTracker.PARKED_CAR_THRESHOLD * 1000 && 
               avgMovementPerFrame < VehicleTracker.MOVEMENT_THRESHOLD &&
               isActive();
    }
    
    public RectF getLastBox() {
        return positions.isEmpty() ? null : positions.get(positions.size() - 1);
    }
    
    public Detection getLastDetection() {
        return detections.isEmpty() ? null : detections.get(detections.size() - 1);
    }
    
    public float getAverageSpeed(float pixelsToMeters, float fps) {
        if (positions.size() < 2) return 0;
        
        float totalDistance = 0;
        for (int i = 1; i < positions.size(); i++) {
            RectF prev = positions.get(i - 1);
            RectF curr = positions.get(i);
            
            float dx = curr.centerX() - prev.centerX();
            float dy = curr.centerY() - prev.centerY();
            totalDistance += Math.sqrt(dx * dx + dy * dy);
        }
        
        float distanceInMeters = totalDistance * pixelsToMeters;
        float timeInSeconds = (positions.size() - 1) / fps;
        
        return (distanceInMeters / timeInSeconds) * 3.6f; // Convert to km/h
    }
}
