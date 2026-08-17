package hpc.cluster;

/**
 * Type of a node in the registry.
 *
 * FIXED    - lab machine or VM, part of the tightly-coupled MPI cluster.
 * MOBILE   - phone/tablet, part of the loosely-coupled Grid layer only.
 * IOT      - IoT device, part of the loosely-coupled Grid layer only.
 */
public enum NodeType {
    FIXED,
    MOBILE,
    IOT
}
