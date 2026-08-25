package vtmachine.model;

import vtmachine.model.Sim.IoDevice;
import vtmachine.model.Sim.LifecyclePhase;
import vtmachine.model.Sim.Outcome;
import vtmachine.model.Sim.TaskProfile;
import vtmachine.model.Sim.VtState;

/** Read-only virtual-thread state shared by the live model and replay frames. */
public interface ThreadView {
    long id();
    VtState state();
    double x();
    double y();
    double z();
    double work();
    double work0();
    TaskProfile profile();
    double plannedIoSeconds();
    IoDevice ioDevice();
    double io();
    boolean resumed();
    boolean hero();
    boolean live();
    boolean isTweening();
    LifecyclePhase lifecyclePhase();
    double lifecycleSeconds(LifecyclePhase phase, double now);
    double lifecycleAge(double now);
    int scopeId();
    int scopeChildIndex();
    Outcome outcome();
    boolean resourcePermit();
    boolean waitingForPermit();
    int carrierIndex();
    boolean carrierPinned();
}
