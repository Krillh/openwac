# OpenWAC

OpenWAC is a Minecraft mod highly inspired by such mods as OpenComputers and
its successors (OpenComputers II and its "modern" counterpart). The ability to
not only write code *in Minecraft* that runs, but also write your own Operating
System or even bootloader was incredible. It allowed for such a rich and nerdy
group of people to take on the challenge of writing their own programs! However,
there was an evil lurking. If you wanted to run something, it had to be in Lua.
As an avid Rustacean, I find that mildly appalling. I gave it my best shot, but
simply could not enjoy writing Lua at all. I understand why it was the choice
made then, but it gave me an idea. What if there was a similar mod, but instead
of Lua, the computers ran WebAssembly? It's nearly the most low level portable
language out there. And so, that is where OpenWAC (Open WebAssembly Computers)
started.

This way, someone can write code in C, C++, C3, Rust, Zig, Odin, Hare, Go, LO,
and you guessed it, Lua; it'll compile to WASM, and it'll run. It's fast, but
I'll admit in my short anecdotal testing, it's not as fast as Lua (yet). The
mod is still in its first working version.

For specific information, refer to the `docs` folder.
