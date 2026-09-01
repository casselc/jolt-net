(ns jolt.net.ffi-test
  (:require [jolt.ffi :as ffi]
            [jolt.net.check :as c]
            [jolt.net.ffi :as nffi]))

(defn run! []
  (c/section "ffi: write-order compatibility")
  ;; The public four-argument order changed in Jolt 0.8.0. Keep both the
  ;; offset and value nonzero and distinct so swapping them cannot pass.
  (let [p (ffi/alloc 96)]
    (try
      (ffi/write-array p (byte-array 96))
      (nffi/write-at! p :uint8 19 73)
      (c/check "write-at! places the value at the requested nonzero offset"
               73 (ffi/read p :uint8 19))
      (c/check "write-at! does not mistake the value for the offset"
               0 (ffi/read p :uint8 73))
      (finally
        (ffi/free p)))))
