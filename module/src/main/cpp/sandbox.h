#pragma once
// Explicit opt-in, irreversible until process restart. Blocks creation of new
// INET/INET6/PACKET sockets and io_uring. Does not inspect paths or deny Binder.
// Existing sockets, received descriptors and IPC proxies are outside this gate.
int install_network_filter();
