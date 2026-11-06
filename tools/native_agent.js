'use strict';

// Metadata only: do not copy file, audio, screen, or network payloads.
const hooks = new Set();
const seen = new Set();
let reporting = false;
function emit(kind, data) {
  if (reporting) return;
  reporting = true;
  try { send({time: new Date().toISOString(), tid: Process.getCurrentThreadId(), kind, ...data}); }
  finally { reporting = false; }
}
function stack(context) {
  try { return Thread.backtrace(context, Backtracer.ACCURATE).slice(0, 12).map(a => DebugSymbol.fromAddress(a).toString()); }
  catch (_) { return []; }
}
function cstring(address, limit = 1024) {
  try { return address.isNull() ? null : address.readUtf8String(limit).split('\0')[0]; }
  catch (_) { try { return address.readCString(); } catch (_) { return '<unreadable>'; } }
}
const libc = Process.getModuleByName('libc.so');
const readlink = new NativeFunction(libc.getExportByName('readlink'), 'long', ['pointer', 'pointer', 'ulong']);
function fdPath(fd) {
  const path = Memory.allocUtf8String('/proc/self/fd/' + fd);
  const buffer = Memory.alloc(1024);
  const size = Number(readlink(path, buffer, 1023));
  return size > 0 ? buffer.readUtf8String(size) : null;
}
function endpoint(fd) {
  try {
    const type = Socket.type(fd);
    if (!['tcp', 'tcp6', 'udp', 'udp6'].includes(type)) return null;
    return {type, local: Socket.localAddress(fd), peer: Socket.peerAddress(fd)};
  } catch (_) { return null; }
}
function sockaddr(address) {
  try {
    if (address.isNull()) return null;
    const family = address.readU16();
    const port = (address.add(2).readU8() << 8) | address.add(3).readU8();
    if (family === 2) return {family, port, address: [4, 5, 6, 7].map(i => address.add(i).readU8()).join('.')};
    if (family === 10) return {family, port, address: Array.from({length: 8}, (_, i) => ((address.add(8+i*2).readU8()<<8) | address.add(9+i*2).readU8()).toString(16)).join(':')};
    return {family};
  } catch (_) { return null; }
}
function attach(address, label, callbacks) {
  const key = address.toString();
  if (hooks.has(key)) return;
  Interceptor.attach(address, callbacks);
  hooks.add(key);
  emit('hook_installed', {label, address: key});
}
function libcHook(name, callbacks) {
  try { attach(libc.getExportByName(name), name, callbacks); }
  catch (error) { emit('hook_unavailable', {label: name, error: String(error)}); }
}
for (const name of ['connect', 'sendto', 'sendmsg', 'sendmmsg', 'write', 'writev']) {
  libcHook(name, {
    onEnter(args) {
      this.fd = args[0].toInt32();
      this.endpoint = endpoint(this.fd);
      this.destination = name === 'connect' ? sockaddr(args[1]) : name === 'sendto' ? sockaddr(args[4]) : null;
      this.interesting = !reporting && (this.endpoint !== null || this.destination?.family === 2 || this.destination?.family === 10);
      if (this.interesting) this.frames = stack(this.context);
    },
    onLeave(result) {
      if (this.interesting) emit('network_call', {api: name, fd: this.fd, endpoint: this.endpoint, destination: this.destination, result: result.toInt32(), stack: this.frames});
    }
  });
}
for (const name of ['open', 'open64', 'openat', 'openat64']) {
  libcHook(name, {
    onEnter(args) {
      this.path = cstring(args[name.startsWith('openat') ? 1 : 0]);
      this.frames = reporting ? [] : stack(this.context);
    },
    onLeave(result) {
      if (this.path && !reporting) emit('file_open', {api: name, path: this.path, result: result.toInt32(), stack: this.frames});
    }
  });
}
for (const name of ['read', 'pread64', 'readv']) {
  libcHook(name, {
    onEnter(args) {
      this.fd = args[0].toInt32();
      this.path = reporting ? null : fdPath(this.fd);
      this.interesting = this.path && /^(\/data\/(user|data|system)|\/sdcard|\/storage|\/dev\/(snd|video|graphics))/.test(this.path);
      if (this.interesting) this.frames = stack(this.context);
    },
    onLeave(result) {
      if (this.interesting) emit('file_read', {api: name, fd: this.fd, path: this.path, result: result.toInt32(), stack: this.frames});
    }
  });
}
function parcelInterface(buffer, size) {
  if (size < 4 || size > 16 * 1024 * 1024) return null;
  for (let offset = 0; offset <= Math.min(size - 4, 48); offset += 4) {
    try {
      const length = buffer.add(offset).readS32();
      if (length < 8 || length > 180 || offset + 4 + length * 2 > size) continue;
      const value = buffer.add(offset + 4).readUtf16String(length);
      if (/^(android\.|vendor\.|com\.)[A-Za-z0-9_.$]+$/.test(value)) return value;
    } catch (_) {}
  }
  return null;
}
function binderCommands(buffer, bytes, direction, frames) {
  let offset = 0;
  while (offset + 4 <= bytes && offset < 65536) {
    try {
      const command = buffer.add(offset).readU32();
      const size = (command >>> 16) & 0x3fff;
      if (offset + 4 + size > bytes) break;
      if ([0x40406300, 0x40406301, 0x40486311, 0x40486312, 0x80407202, 0x80407203, 0x80487202].includes(command) && size >= 64) {
        const tx = buffer.add(offset + 4);
        const dataSize = Number(tx.add(32).readU64());
        emit('binder_transaction', {direction, command: '0x' + command.toString(16), target_or_handle: tx.readU64().toString(), code: tx.add(16).readU32(), flags: tx.add(20).readU32(), sender_pid: tx.add(24).readS32(), sender_euid: tx.add(28).readU32(), data_size: dataSize, interface: parcelInterface(tx.add(48).readPointer(), dataSize), stack: frames});
      }
      offset += 4 + size;
    } catch (_) { break; }
  }
}
libcHook('ioctl', {
  onEnter(args) {
    this.isBinder = args[1].toUInt32() === 0xc0306201;
    if (!this.isBinder || reporting) { this.isBinder = false; return; }
    this.bwr = args[2];
    this.frames = stack(this.context);
    try { binderCommands(this.bwr.add(16).readPointer(), Number(this.bwr.readU64()), 'outgoing', this.frames); } catch (_) {}
  },
  onLeave(result) {
    if (!this.isBinder || result.toInt32() !== 0) return;
    try { binderCommands(this.bwr.add(40).readPointer(), Number(this.bwr.add(32).readU64()), 'incoming', this.frames); } catch (_) {}
  }
});
function cppString(address) {
  try {
    const first = address.readU8();
    if (!(first & 1)) return address.add(1).readUtf8String(Math.min(first >> 1, 256));
    return address.add(16).readPointer().readUtf8String(Math.min(Number(address.add(8).readU64()), 256));
  } catch (_) { return '<unreadable>'; }
}
function hookHelper(module) {
  if (module.name !== 'libsystemhelper_jni.so' || seen.has(module.path)) return;
  seen.add(module.path);
  emit('target_module', {path: module.path, base: module.base.toString(), size: module.size});
  for (const symbol of module.enumerateExports()) {
    if (symbol.type !== 'function') continue;
    const name = symbol.name;
    if (!name.startsWith('Java_com_qualcomm_qti_services_systemhelper_') &&
        !['processRequest', 'acquireWakeLock', 'releaseWakeLock', 'lockRotation', 'isOrientationPortrait'].includes(name) &&
        !((name.includes('SystemEvent') || name.includes('SystemResource') || name.includes('SystemTUICallback')) && /(registerEvent|RegisterEvent|acquire|release|notifyTUI)/.test(name))) continue;
    attach(symbol.address, name, {
      onEnter(args) {
        this.name = name;
        const details = {symbol: name, stack: stack(this.context)};
        if (name.includes('sendEventNotification')) details.event = args[2].toInt32();
        if (name === 'processRequest') details.request = cppString(args[0]);
        if (name === 'lockRotation') details.lock = args[0].toInt32();
        if (/(registerEvent|RegisterEvent|acquireSurface|SystemResource.*acquire)/.test(name)) details.arg1 = args[1].toString();
        emit('helper_enter', details);
      },
      onLeave(result) { emit('helper_leave', {symbol: this.name, result: result.toString()}); }
    });
  }
}
Process.attachModuleObserver({onAdded(module) { hookHelper(module); }});
emit('ready', {pid: Process.id, hook_count: hooks.size, modules: Process.enumerateModules().map(m => ({name:m.name,path:m.path}))});
