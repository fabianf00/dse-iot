package at.ac.univie.dse.iot.msml.jobs;

/** A unit of work executed by a worker of the {@link WorkerPool} (Command). */
public interface Job {

    void execute();

    /** Short description for logs. */
    String name();
}
