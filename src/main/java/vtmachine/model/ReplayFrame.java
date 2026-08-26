package vtmachine.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import vtmachine.model.Sim.Flash;
import vtmachine.model.Sim.IoDevice;
import vtmachine.model.Sim.JdkComparison;
import vtmachine.model.Sim.JdkShowdown;
import vtmachine.model.Sim.LifecyclePhase;
import vtmachine.model.Sim.Outcome;
import vtmachine.model.Sim.ProfileStats;
import vtmachine.model.Sim.ResourcePoolStats;
import vtmachine.model.Sim.Scenario;
import vtmachine.model.Sim.ScopeStats;
import vtmachine.model.Sim.Stats;
import vtmachine.model.Sim.TaskProfile;
import vtmachine.model.Sim.VtState;

/** Immutable display snapshot used to inspect history without rewinding the live model. */
public record ReplayFrame(double time, double bootT, int chapter, boolean freeRun,
        boolean liveMode, int maxThreads, Scenario scenario, JdkComparison jdkComparison,
        JdkShowdown jdkShowdown, int scenarioSubmitted,
        Stats stats, ProfileStats profileStats, double averageIoSeconds,
        double carrierUtilization, ResourcePoolStats resourcePoolStats,
        List<ScopeStats> structuredScopes, List<String> log,
        Map<Flash, Double> flashAges, List<CarrierFrame> carriers,
        List<ThreadFrame> vts) {

    public record CarrierFrame(int index, boolean pinned, double heat, long mountedVtId) {}

    public record ThreadFrame(long id, VtState state, double x, double y, double z, double work, double work0,
            TaskProfile profile, double plannedIoSeconds, IoDevice ioDevice, double io,
            boolean resumed, boolean hero, boolean live, boolean isTweening,
            LifecyclePhase lifecyclePhase, double runnableSeconds, double mountedSeconds,
            double parkedSeconds, double terminatedSeconds,
            double capturedLifecycleAge, int scopeId, int scopeChildIndex, Outcome outcome,
            boolean resourcePermit, boolean waitingForPermit, int carrierIndex,
            boolean carrierPinned) implements ThreadView {

        @Override
        public double lifecycleSeconds(LifecyclePhase phase, double ignoredNow) {
            return switch (phase) {
                case RUNNABLE -> runnableSeconds;
                case MOUNTED -> mountedSeconds;
                case PARKED -> parkedSeconds;
                case TERMINATED -> terminatedSeconds;
            };
        }

        @Override
        public double lifecycleAge(double ignoredNow) {
            return capturedLifecycleAge;
        }
    }

    public static ReplayFrame capture(Sim sim) {
        double now = sim.time();
        List<ThreadFrame> threadFrames = sim.vts().stream().map(vt -> {
            int carrierIndex = vt.carrier() == null ? -1 : vt.carrier().index();
            boolean carrierPinned = vt.carrier() != null && vt.carrier().pinned();
            return new ThreadFrame(vt.id(), vt.state(), vt.pos().x, vt.pos().y, vt.pos().z, vt.work(), vt.work0(),
                    vt.profile(), vt.plannedIoSeconds(), vt.ioDevice(), vt.io(), vt.resumed(),
                    vt.hero(), vt.live(), vt.isTweening(), vt.lifecyclePhase(),
                    vt.lifecycleSeconds(LifecyclePhase.RUNNABLE, now),
                    vt.lifecycleSeconds(LifecyclePhase.MOUNTED, now),
                    vt.lifecycleSeconds(LifecyclePhase.PARKED, now),
                    vt.lifecycleSeconds(LifecyclePhase.TERMINATED, now),
                    vt.lifecycleAge(now), vt.scopeId(), vt.scopeChildIndex(), vt.outcome(),
                    vt.resourcePermit(), vt.waitingForPermit(), carrierIndex, carrierPinned);
        }).toList();
        List<CarrierFrame> carrierFrames = sim.carriers().stream()
                .map(carrier -> new CarrierFrame(carrier.index(), carrier.pinned(), carrier.heat(),
                        carrier.mounted() == null ? -1 : carrier.mounted().id()))
                .toList();
        EnumMap<Flash, Double> flashAges = new EnumMap<>(Flash.class);
        for (Flash flash : Flash.values()) flashAges.put(flash, sim.flashAge(flash));
        return new ReplayFrame(now, sim.bootT(), sim.chapter(), sim.freeRun(), sim.liveMode(),
                sim.maxThreads(), sim.scenario(), sim.jdkComparison(), sim.jdkShowdown(),
                sim.scenarioSubmitted(), sim.stats(),
                sim.profileStats(), sim.averageIoSeconds(), sim.carrierUtilization(),
                sim.resourcePoolStats(), sim.structuredScopes(), List.copyOf(sim.log()),
                Map.copyOf(flashAges), carrierFrames, threadFrames);
    }
}
