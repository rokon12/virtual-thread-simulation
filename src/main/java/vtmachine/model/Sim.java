package vtmachine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import vtmachine.model.Vt.Tween;
import vtmachine.model.Vt.Tween.Arrival;

/**
 * JavaFX-free simulation model. All mutations happen on the caller's thread.
 */
public final class Sim {
    public static final int DEFAULT_CARRIERS = 4;
    public static final int DEFAULT_MAX_THREADS = 500;
    public static final double DEFAULT_TASK_RATE = 1.4;

    public record Stats(int runnable, int mounted, int parked, int completed) {}

    public enum VtState {
        TO_QUEUE("toQueue"), QUEUED("queued"), MOUNTING("mounting"),
        RUNNING("running"), PARKING("parking"), PARKED("parked"),
        DONE("done"), DEAD("dead");

        private final String display;

        VtState(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum Flash { MOUNT, PARK, RESUME, PIN }

    private final int maxThreads;
    private final double taskRate;
    private final long seed;
    private RandomGenerator random;

    private final List<Vt> vts = new ArrayList<>();
    private final List<Vt> readOnlyVts = Collections.unmodifiableList(vts);
    private final List<Vt> queue = new ArrayList<>();
    private final List<Carrier> carriers = new ArrayList<>();
    private final List<Carrier> readOnlyCarriers = Collections.unmodifiableList(carriers);
    private final Deque<String> log = new ArrayDeque<>(9);
    private final EnumMap<Flash, Double> flashes = new EnumMap<>(Flash.class);

    private long nextId;
    private double time;
    private double bootT;
    private double speed = 0.75;
    private double spawnRate;
    private double spawnAcc;
    private int burst;
    private int completed;
    private int chapter;
    private boolean running = true;
    private boolean freeRun;
    private boolean chaos;
    private boolean pendingPark;
    private boolean pendingPin;
    private boolean bootAutoAdvanced;
    private Vt hero;

    public Sim() {
        this(DEFAULT_CARRIERS, DEFAULT_MAX_THREADS, DEFAULT_TASK_RATE, System.nanoTime());
    }

    public Sim(int carrierCount, int maxThreads, double taskRate, long seed) {
        if (carrierCount < 2 || carrierCount > 6) {
            throw new IllegalArgumentException("carriers must be between 2 and 6");
        }
        if (maxThreads < 50 || maxThreads > 800) {
            throw new IllegalArgumentException("maxThreads must be between 50 and 800");
        }
        if (taskRate < 0.3 || taskRate > 6.0) {
            throw new IllegalArgumentException("taskRate must be between 0.3 and 6.0");
        }
        this.maxThreads = maxThreads;
        this.taskRate = taskRate;
        this.seed = seed;
        reset(carrierCount);
    }

    /** Advance one simulation step. The application supplies an already speed-scaled dt. */
    public void tick(double dt) {
        if (!running || dt <= 0) return;
        time += dt;

        if (bootT < 3.0) {
            double before = bootT;
            bootT = Math.min(3.0, bootT + dt);
            if (before < 3.0 && bootT >= 3.0 && !bootAutoAdvanced) {
                bootAutoAdvanced = true;
                addLog("scheduler online");
                gotoChapter(1);
            }
            return;
        }

        spawnPhase(dt);
        tweenPhase(dt);
        driftPhase(dt);
        carrierPhase(dt);
        runPhase(dt);
        parkedPhase(dt);
        reapPhase();
    }

    private void spawnPhase(double dt) {
        spawnAcc += spawnRate * dt;
        int spawnedThisFrame = 0;
        while ((spawnAcc >= 1 || burst > 0) && vts.size() < maxThreads && spawnedThisFrame < 6) {
            if (burst > 0) burst--;
            else spawnAcc -= 1;
            spawn();
            spawnedThisFrame++;
        }
    }

    private void tweenPhase(double dt) {
        for (Vt vt : vts) {
            Tween tween = vt.tween;
            if (tween == null) continue;

            tween.elapsed += dt;
            double t = Math.min(1, tween.elapsed / tween.duration);
            double e = t * t * (3 - 2 * t);
            vt.pos.set(
                    lerp(tween.from.x, tween.to.x, e),
                    lerp(tween.from.y, tween.to.y, e) + Math.sin(Math.PI * e) * tween.arc,
                    lerp(tween.from.z, tween.to.z, e));

            if (t >= 1) {
                vt.pos.set(tween.to);
                vt.tween = null;
                vt.state = switch (tween.arrival) {
                    case QUEUED -> VtState.QUEUED;
                    case RUNNING -> VtState.RUNNING;
                    case PARKED -> VtState.PARKED;
                    case DEAD -> VtState.DEAD;
                };
            }
        }
    }

    private void driftPhase(double dt) {
        double k = Math.min(1, 4 * dt);
        for (int i = 0; i < queue.size(); i++) {
            Vt vt = queue.get(i);
            if (vt.state == VtState.QUEUED && vt.tween == null) {
                drift(vt.pos, queueSlot(i), k);
            }
        }

        int parkedIndex = 0;
        for (Vt vt : vts) {
            if (vt.state == VtState.PARKED && vt.tween == null) {
                drift(vt.pos, heapSlot(parkedIndex++), k);
            }
        }
    }

    private void carrierPhase(double dt) {
        for (Carrier carrier : carriers) {
            carrier.heat = Math.max(0, carrier.heat - 0.8 * dt);
            if (carrier.pinT > 0) {
                carrier.pinT -= dt;
                if (carrier.pinT <= 0) {
                    carrier.pinT = 0;
                    if (carrier.mounted != null) {
                        addLog("VT-" + carrier.mounted.id + " unpinned · resumes");
                    }
                }
            }

            if (carrier.mounted == null) {
                Vt next = firstMountable();
                if (next != null) mount(next, carrier);
            }
        }
    }

    private void runPhase(double dt) {
        for (Vt vt : vts) {
            if (vt.state != VtState.RUNNING) continue;
            Carrier carrier = vt.carrier;
            carrier.heat = Math.min(1, carrier.heat + 2 * dt);

            if (pendingPark) {
                pendingPark = false;
                park(vt);
                continue;
            }
            if (pendingPin) {
                pendingPin = false;
                pin(vt);
            }
            if (carrier.pinned()) continue;

            vt.work -= dt;
            if (chaos && vt.work > 0.4) {
                double chance = random.nextDouble();
                if (!vt.resumed && chance < 0.30 * dt) {
                    park(vt);
                    continue;
                } else if (chance > 1 - 0.03 * dt) {
                    pin(vt);
                }
            }

            if (vt.work <= 0) complete(vt, carrier);
        }
    }

    private void parkedPhase(double dt) {
        for (Vt vt : vts) {
            if (vt.state != VtState.PARKED) continue;
            vt.io -= dt;
            if (vt.io <= 0) {
                vt.state = VtState.TO_QUEUE;
                vt.resumed = true;
                queue.addFirst(vt);
                tween(vt, queueSlot(0), 0.85, Arrival.QUEUED);
                flash(Flash.RESUME);
                addLog("VT-" + vt.id + " I/O done · runnable");
            }
        }
    }

    private void reapPhase() {
        for (Iterator<Vt> iterator = vts.iterator(); iterator.hasNext();) {
            Vt vt = iterator.next();
            if (vt.state == VtState.DEAD) {
                iterator.remove();
                if (hero == vt) hero = null;
            }
        }
    }

    private void spawn() {
        double work = 1.6 + random.nextDouble() * 2.8;
        Vt vt = new Vt(nextId++, new Vec3(
                -130 + random.nextDouble() * 20 - 10,
                105 + random.nextDouble() * 12,
                random.nextDouble() * 20 - 10), work);
        if (hero == null) {
            hero = vt;
            vt.hero = true;
        }
        tween(vt, queueSlot(queue.size()), 0.7, Arrival.QUEUED);
        queue.add(vt);
        vts.add(vt);
    }

    private Vt firstMountable() {
        for (int i = 0; i < queue.size(); i++) {
            Vt vt = queue.get(i);
            if (vt.state == VtState.QUEUED && vt.tween == null) {
                queue.remove(i);
                return vt;
            }
        }
        return null;
    }

    private void mount(Vt vt, Carrier carrier) {
        carrier.mounted = vt;
        vt.carrier = carrier;
        vt.state = VtState.MOUNTING;
        tween(vt, new Vec3(laneX(carrier.index()), 30, 0), 0.55, Arrival.RUNNING);
        flash(Flash.MOUNT);
        addLog("VT-" + vt.id + (vt.resumed ? " resumed on C" : " mounted on C") + (carrier.index() + 1));
    }

    private void park(Vt vt) {
        Carrier carrier = vt.carrier;
        if (carrier != null) carrier.mounted = null;
        vt.carrier = null;
        vt.state = VtState.PARKING;
        vt.io = 1.8 + random.nextDouble() * 3.0;
        int parkedCount = 0;
        for (Vt candidate : vts) {
            if (candidate != vt && (candidate.state == VtState.PARKED || candidate.state == VtState.PARKING)) {
                parkedCount++;
            }
        }
        tween(vt, heapSlot(parkedCount), 0.85, Arrival.PARKED);
        flash(Flash.PARK);
        addLog("VT-" + vt.id + " parked · carrier released");
    }

    private void pin(Vt vt) {
        if (vt.carrier == null) return;
        vt.carrier.pinT = 2.6 + random.nextDouble() * 1.2;
        flash(Flash.PIN);
        addLog("VT-" + vt.id + " PINNED on C" + (vt.carrier.index() + 1));
    }

    private void complete(Vt vt, Carrier carrier) {
        carrier.mounted = null;
        vt.carrier = null;
        vt.state = VtState.DONE;
        tween(vt, new Vec3(150, -14, 40), 0.8, Arrival.DEAD);
        completed++;
        addLog("VT-" + vt.id + " completed · C" + (carrier.index() + 1) + " free");
    }

    private void tween(Vt vt, Vec3 target, double duration, Arrival arrival) {
        vt.tween = new Tween(new Vec3(vt.pos), target, duration, 8 + random.nextDouble() * 10, arrival);
    }

    private static void drift(Vec3 pos, Vec3 target, double k) {
        pos.x += (target.x - pos.x) * k;
        pos.y += (target.y - pos.y) * k;
        pos.z += (target.z - pos.z) * k;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public boolean forcePark() {
        for (Vt vt : vts) {
            if (vt.state == VtState.RUNNING && vt.carrier != null && !vt.carrier.pinned()) {
                park(vt);
                return true;
            }
        }
        addLog("no unpinned running VT");
        return false;
    }

    public boolean forcePin() {
        for (Vt vt : vts) {
            if (vt.state == VtState.RUNNING && vt.carrier != null && !vt.carrier.pinned()) {
                pin(vt);
                return true;
            }
        }
        addLog("no running VT");
        return false;
    }

    public void burst(int count) {
        if (count <= 0) return;
        burst += count;
        addLog("burst: " + count + " tasks submitted");
    }

    public void gotoChapter(int requested) {
        int selected = Math.floorMod(requested, 6);
        chapter = selected;
        freeRun = false;
        chaos = false;
        spawnRate = 0;
        pendingPark = false;
        pendingPin = false;

        switch (selected) {
            case 0 -> reset(carriers.isEmpty() ? DEFAULT_CARRIERS : carriers.size());
            case 1 -> {
                burst += 6;
                addLog("chapter: mount");
            }
            case 2 -> {
                if (vts.stream().noneMatch(v -> v.state == VtState.RUNNING)) burst += 4;
                pendingPark = true;
                addLog("chapter: park");
            }
            case 3 -> {
                Vt parked = vts.stream().filter(v -> v.state == VtState.PARKED).findFirst().orElse(null);
                if (parked != null) parked.io = Math.min(parked.io, 0.8);
                else {
                    burst += 2;
                    pendingPark = true;
                }
                addLog("chapter: resume");
            }
            case 4 -> {
                if (vts.stream().noneMatch(v -> v.state == VtState.RUNNING)) burst += 4;
                pendingPin = true;
                addLog("chapter: pinned");
            }
            case 5 -> {
                burst += Math.max(0, maxThreads - vts.size());
                chaos = true;
                addLog("chapter: scale — flooding tasks");
            }
            default -> throw new AssertionError("unreachable");
        }
    }

    public void setFreeRun(boolean enabled) {
        freeRun = enabled;
        chaos = enabled;
        spawnRate = enabled ? taskRate : 0;
        addLog(enabled ? "free run: continuous load" : "guided mode");
    }

    public void reset(int carrierCount) {
        if (carrierCount < 2 || carrierCount > 6) {
            throw new IllegalArgumentException("carriers must be between 2 and 6");
        }
        random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        vts.clear();
        queue.clear();
        carriers.clear();
        for (int i = 0; i < carrierCount; i++) carriers.add(new Carrier(i));
        log.clear();
        log.addFirst("boot: power on");
        flashes.clear();
        for (Flash flash : Flash.values()) flashes.put(flash, -9.0);
        nextId = 1;
        time = 0;
        bootT = 0;
        spawnRate = 0;
        spawnAcc = 0;
        burst = 0;
        completed = 0;
        chapter = 0;
        running = true;
        freeRun = false;
        chaos = false;
        pendingPark = false;
        pendingPin = false;
        bootAutoAdvanced = false;
        hero = null;
    }

    private void flash(Flash flash) {
        flashes.put(flash, time);
    }

    private void addLog(String message) {
        String formatted = "%03ds %s".formatted((int) Math.floor(time), message);
        log.addFirst(formatted);
        while (log.size() > 9) log.removeLast();
    }

    public Vec3 queueSlot(int index) {
        double angle = index * 0.55;
        double radius = 7 + 2.6 * Math.sqrt(index);
        return new Vec3(Math.cos(angle) * radius * 1.7, 80.5, Math.sin(angle) * radius * 0.62);
    }

    public Vec3 heapSlot(int index) {
        int level = index / 25;
        int item = index % 25;
        return new Vec3(118 + (item % 5 - 2) * 6.4, 34 + level * 6.4,
                (item / 5 - 2) * 6.4);
    }

    public double laneX(int index) {
        return (index - (carriers.size() - 1) / 2.0) * 26;
    }

    public List<Vt> vts() { return readOnlyVts; }
    public List<Carrier> carriers() { return readOnlyCarriers; }
    public Deque<String> log() { return new ArrayDeque<>(log); }
    public Vt hero() { return hero; }
    public int chapter() { return chapter; }
    public double bootT() { return bootT; }
    public double time() { return time; }
    public double speed() { return speed; }
    public boolean running() { return running; }
    public boolean freeRun() { return freeRun; }
    public int maxThreads() { return maxThreads; }
    public long seed() { return seed; }

    public void setRunning(boolean running) { this.running = running; }
    public void setSpeed(double speed) { this.speed = Math.max(0.25, Math.min(3.0, speed)); }

    public Stats stats() {
        int mounted = 0;
        for (Carrier carrier : carriers) if (carrier.mounted != null) mounted++;
        int parked = 0;
        for (Vt vt : vts) if (vt.state == VtState.PARKED || vt.state == VtState.PARKING) parked++;
        return new Stats(queue.size(), mounted, parked, completed);
    }

    public double flashAge(Flash flash) {
        return Math.max(0, time - flashes.getOrDefault(flash, -9.0));
    }
}
