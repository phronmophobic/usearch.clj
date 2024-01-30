(ns com.phronemophobic.usearch.raw
  (:require [com.phronemophobic.clong.gen.jna :as gen]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [com.rpl.specter :as specter])
  (:import java.lang.ref.Cleaner)
  (:gen-class))



(def cleaner (Cleaner/create))

(defn ^:private write-edn [w obj]
  (binding [*print-length* nil
            *print-level* nil
            *print-dup* false
            *print-meta* false
            *print-readably* true

            ;; namespaced maps not part of edn spec
            *print-namespace-maps* false

            *out* w]
    (pr obj)))

(def lib-options
  {com.sun.jna.Library/OPTION_STRING_ENCODING "UTF8"})
(def ^:no-doc lib-usearch
  (com.sun.jna.NativeLibrary/getInstance "usearch_c" lib-options))


(def default-arguments
  ["-resource-dir"
"/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/clang/15.0.0"
"-isysroot"
"/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"
"-I/usr/local/include"
"-internal-isystem"
"/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk/usr/local/include"
"-internal-isystem"
"/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/clang/15.0.0/include"
"-internal-externc-isystem"
"/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk/usr/include"
"-internal-externc-isystem"
"/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/include"
])

(defn ^:private dump-api []
  (let [outf (io/file
              "resources"
              "com"
              "phronemophobic"
              "usearch"
              "api.edn")]
    (.mkdirs (.getParentFile outf))
    (with-open [w (io/writer outf)]
      (write-edn w
                 ((requiring-resolve 'com.phronemophobic.clong.clang/easy-api)
                  "/Users/adrian/workspace/usearch/c/usearch.h"
                  default-arguments)))))


(def api
  (with-open [rdr (io/reader
                     (io/resource
                      "com/phronemophobic/usearch/api.edn"))
                rdr (java.io.PushbackReader. rdr)]
      (edn/read rdr)))


(gen/def-api lib-usearch api)

(let [struct-prefix (gen/ns-struct-prefix *ns*)]
  (defmacro import-structs! []
    `(gen/import-structs! api ~struct-prefix)))


