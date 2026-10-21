;; Source for the bundled diagnostic BIOS, not an operating system.
;; Runtime loading uses only the compiled bootstrap-v1.wasm resource.
(module
  (import "hal" "hal_chat_print" (func $print (param i32 i32) (result i32)))
  (import "hal" "hal_yield" (func $yield))
  (memory (export "memory") 1 1)
  (data (i32.const 0) "OpenWAC BIOS ready.")
  (func (export "_start")
    i32.const 0
    i32.const 19
    call $print
    drop
    (loop $idle
      call $yield
      br $idle)))
