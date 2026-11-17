package dev.begleiter.nav;

import java.util.ArrayList;
import java.util.List;

/** Zuschaltbare, begrenzte Diagnose je Tick. Wird nie gespeichert und nie übers Netz geschickt. */
public final class MotionTrace {
    public static final int CAPACITY = 1024;
    public record Sample(int tick, double x, double y, double z, double speed, float bodyYaw, float headYaw,
                         double targetX, double targetZ, String owner, String state, int index, double progress,
                         String event, long searchNanos, long validationNanos, long steeringNanos) {}
    public record Metrics(int samples, int pauseTicks, int pauses, double distance, double yawP95,
                          double accelerationP95, long searchNanos, long validationNanos, long steeringNanos,
                          long recordingNanos, double searchP95Ms, double validationP95Ms, double steeringP95Ms,
                          double totalMaxMs) {}

    private Sample[] ring;
    private int next, size;
    private String event = "";
    private long recordingNanos;

    public void enable() { if (ring == null) ring = new Sample[CAPACITY]; }
    public void disable() { ring = null; next = size = 0; event = ""; recordingNanos = 0; }
    public boolean enabled() { return ring != null; }
    public void event(String value) { if (enabled()) event = value; }
    public String takeEvent() { String value = event; event = ""; return value; }

    public void record(Sample sample) {
        if (ring == null) return;
        long start = System.nanoTime();
        int previous = (next + CAPACITY - 1) % CAPACITY;
        if (size > 0 && ring[previous].tick() == sample.tick()) {
            if (sample.event().isEmpty() && !ring[previous].event().isEmpty()) {
                sample = new Sample(sample.tick(), sample.x(), sample.y(), sample.z(), sample.speed(), sample.bodyYaw(), sample.headYaw(),
                        sample.targetX(), sample.targetZ(), sample.owner(), sample.state(), sample.index(), sample.progress(), ring[previous].event(),
                        sample.searchNanos(), sample.validationNanos(), sample.steeringNanos());
            }
            ring[previous] = sample;
        }
        else {
            ring[next] = sample;
            next = (next + 1) % CAPACITY;
            size = Math.min(CAPACITY, size + 1);
        }
        recordingNanos += System.nanoTime() - start;
    }

    public List<Sample> samples() {
        List<Sample> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(ring[(next - size + i + CAPACITY) % CAPACITY]);
        return List.copyOf(result);
    }

    public Metrics metrics() {
        var samples = samples();
        List<Double> angles = new ArrayList<>(), accelerations = new ArrayList<>();
        List<Double> searches = new ArrayList<>(), validations = new ArrayList<>(), steerings = new ArrayList<>();
        int run = 0, pauses = 0, pauseTicks = 0;
        double distance = 0;
        double totalMax = 0;
        long search = 0, validation = 0, steering = 0;
        Sample previous = null;
        for (Sample s : samples) {
            if (s.state().equals("WALK") && s.speed() < 0.02) {
                if (++run == 2) { pauses++; pauseTicks += 2; }
                else if (run > 2) pauseTicks++;
            } else run = 0;
            if (previous != null) {
                distance += Math.hypot(s.x() - previous.x(), s.z() - previous.z());
                angles.add(Math.abs(wrap(s.bodyYaw() - previous.bodyYaw())));
                accelerations.add(Math.abs(s.speed() - previous.speed()));
            }
            search += s.searchNanos(); validation += s.validationNanos(); steering += s.steeringNanos();
            searches.add(s.searchNanos() / 1e6); validations.add(s.validationNanos() / 1e6); steerings.add(s.steeringNanos() / 1e6);
            totalMax = Math.max(totalMax, (s.searchNanos() + s.validationNanos() + s.steeringNanos()) / 1e6);
            previous = s;
        }
        return new Metrics(size, pauseTicks, pauses, distance, percentile(angles), percentile(accelerations),
                search, validation, steering, recordingNanos, percentile(searches), percentile(validations), percentile(steerings), totalMax);
    }

    private static double wrap(double degrees) { return ((degrees % 360) + 540) % 360 - 180; }
    private static double percentile(List<Double> values) {
        if (values.isEmpty()) return 0;
        values.sort(Double::compare);
        return values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95) - 1));
    }
}
