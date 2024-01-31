(ns com.phronemophobic.usearch
  (:refer-clojure :exclude [;;contains?
                            ;;count
                            ;;get
                            load remove
                            ])
  (:require [com.phronemophobic.usearch.raw :as raw]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import com.sun.jna.ptr.FloatByReference
           com.sun.jna.Memory
           com.sun.jna.Structure
           com.sun.jna.Pointer
           com.sun.jna.ptr.PointerByReference
           java.lang.ref.Cleaner)
  (:gen-class))

(raw/import-structs!)


(defprotocol IVectorKind
  (vector-kind [this]))

(extend-protocol IVectorKind
  (Class/forName "[F")
  (vector-kind [_] raw/usearch_scalar_f32_k))
(extend-protocol IVectorKind
  (Class/forName "[D")
  (vector-kind [_] raw/usearch_scalar_f64_k))
(extend-protocol IVectorKind
  (Class/forName "[B")
  (vector-kind [_] raw/usearch_scalar_i8_k))


(defmacro ^:private with-error [fn-call]
  `(let [error*# (PointerByReference.)
         ret# (~@fn-call error*#)]
     (when-let [str*# (.getValue error*#)]
       (throw (Exception. (.getString str*# 0))))
     ret#))

;; raw/usearch_add <f>
(defn add
  "Adds a vector with a key to the index.

  `index`: A usearch index.
  `key`: An integer key
  `vec`: A vector to add to the index."
  ([index key vec]
   (with-error
     (raw/usearch_add index key vec (vector-kind vec))))
  ([index key vec vector-kind]
   (with-error
     (raw/usearch_add index key vec vector-kind))))

(defn reserve
  "Sets capacity to `capacity`.

  Has no effect if `capacity` is lower than the index's current capacity."
  [index capacity]
  (with-error
    (raw/usearch_reserve index capacity)))

(defn ^:private ->opts [m]
  (doseq [required-key [:dimensions
                        :quantization]]
    (when (not (clojure.core/get m required-key))
      (throw (Exception. (str required-key " is required.")))))
  (reduce-kv
   (fn [^usearch_init_options_tByReference
        opts k v]
     (case k
       :dimensions (.writeField opts "dimensions" (long v))
       :metric (.writeField opts "metric_kind"
                            (case v
                              :metric/cos raw/usearch_metric_cos_k 
                              :metric/divergence raw/usearch_metric_divergence_k 
                              :metric/hamming raw/usearch_metric_hamming_k 
                              :metric/haversine raw/usearch_metric_haversine_k 
                              :metric/ip raw/usearch_metric_ip_k 
                              :metric/jaccard raw/usearch_metric_jaccard_k 
                              :metric/l2sq raw/usearch_metric_l2sq_k 
                              :metric/pearson raw/usearch_metric_pearson_k 
                              :metric/sorensen raw/usearch_metric_sorensen_k 
                              :metric/tanimoto raw/usearch_metric_tanimoto_k))
       :quantization (.writeField opts "quantization"
                                  (case v
                                    :quantization/f32 raw/usearch_scalar_f32_k
                                    :quantization/f64 raw/usearch_scalar_f64_k
                                    :quantization/i8 raw/usearch_scalar_i8_k))
       :connectivity (.writeField opts "connectivity" (long v))
       :expansions-add (.writeField opts "expansion_add" (long v))
       :expansions-search (.writeField opts "expansion_search" (long v))
       ;; else ignore
       opts)
     ;; return opts
     opts)
   (usearch_init_options_tByReference.)
   (if (:metric m)
     m
     ;; use :metric/ip as default metric
     (assoc m :metric :metric/ip))))

(defn ^:private quant-size [kind]
  (case kind
    ;;raw/usearch_scalar_f32_k
    1 4
    ;; raw/usearch_scalar_f64_k
    2 8
    ;; raw/usearch_scalar_i8_k
    4 1))

(defn ^:private wrap-index [ptr opts]
  (let [kind (long (.readField opts "quantization"))
        dim (.readField opts "dimensions")
        lookup
        (fn [k not-found]
          (case k
            :opts opts
            
            ;; else
            (let [vec*  (Memory. (* (quant-size kind)
                                    dim))
                  num-found (with-error
                          (raw/usearch_get ptr k 1 vec* kind))]
              
              (if (pos? num-found)
                (case kind
                  ;; raw/usearch_scalar_f32_k
                  1 (.getFloatArray vec* 0 dim)
                  ;; raw/usearch_scalar_f64_k
                  2 (.getLongArray vec* 0 dim)
                  ;; raw/usearch_scalar_i8_k
                  4 (.getByteArray vec* 0 dim))
                not-found))))

        index
        (proxy [Pointer
                clojure.lang.ILookup
                clojure.lang.Counted
                clojure.lang.Associative]
            [(Pointer/nativeValue ptr)]

          ;; Counted
          (count []
            (with-error
              (raw/usearch_size ptr)))

          (containsKey [key]
            (not= 0
                  (with-error
                    (raw/usearch_contains ptr key))))

          ;; Lookup
          (valAt
            ([k]
             (lookup k nil))
            ([k not-found]
             (lookup k not-found))))
        raw-ptr (Pointer/nativeValue ptr)]
    (.register ^Cleaner raw/cleaner index
               (fn []
                 (raw/usearch_free (Pointer. raw-ptr) nil)))
    index))

(defn init
  "Creates a new index with `opts`.

  If `vecs` are provided, they will be added to index.
  `vecs` should be a sequence of [key, vec] pairs.

  Required:

  `:dimensions` Length of vectors that will be indexed
  `:metric` The metric used to measure distance. One of
     `:metric/cos`
     `:metric/divergence`
     `:metric/hamming`
     `:metric/haversine`
     `:metric/ip`
     `:metric/jaccard`
     `:metric/l2sq`
     `:metric/pearson`
     `:metric/sorensen`
     `:metric/tanimoto`
  `:quantization` The datatype for the vectors that will be indexed. One of
     `:quantization/f32`
     `:quantization/f64`
     `:quantization/i8`

  Optional:

  `:connectivity` limits connections-per-node in graph
  `:expansions-add` expansion factor used for index construction when adding vectors.
  `:expansions-search` used for index construction during search operations.
"
  ([opts vecs]
   (let [opts* (->opts opts)]
     (let [index 
           (with-error
             (raw/usearch_init opts*))]
       (reserve index (clojure.core/count vecs))
       (let [kind (vector-kind (-> vecs first second))]
         (doseq [[key vec] vecs]
           (add index key vec kind )))
       (wrap-index index opts*))))
  ([opts]
   (let [opts* (->opts opts)]
     (let [index 
           (with-error
             (raw/usearch_init opts*))]
       ;; prevents crashes when trying to add without
       ;; reserving capacity
       (reserve index 0)
       (wrap-index index opts*)))))


;; raw/usearch_capacity <f>
(defn capacity
  "The currently reserved capacity of `index`."
  [index]
  (with-error
    (raw/usearch_capacity index)))
;; raw/usearch_connectivity <f>

;; raw/usearch_contains <f>
#_(defn contains? [index key]
  (with-error
    (raw/usearch_contains index key)))

;; raw/usearch_count <f>
#_(defn count [index key]
  (with-error
    (raw/usearch_count index key)))

;; raw/usearch_dimensions <f>

;; raw/usearch_distance <f>
;; raw/usearch_exact_search <f>
;; (defn exact-search [])
;; raw/usearch_free <f>
#_(defn free [index]
  (with-error
    (raw/usearch_free index)))
;; raw/usearch_get <f>
#_(defn get [index key]
  )
;; raw/usearch_init <f>





;; raw/usearch_load <f>
(defn load
  "Loads the index from `path`. `index` must have already been initialized."
  [index path]
  (with-error
    (raw/usearch_load index path)))
;; raw/usearch_load_buffer <f>
;; (defn load-buffer [index ])
;; raw/usearch_metadata <f>
;; raw/usearch_metadata_buffer <f>
;; raw/usearch_remove <f>
(defn remove
  "Removes the vector associated with the given key from the index."
  [index key]
  (with-error
    (raw/usearch_remove index key)))
;; raw/usearch_rename <f>
(defn rename
  "Renames the vector to map to a different key."
  [index from to]
  (with-error
    (raw/usearch_rename index from to)))
;; raw/usearch_reserve <f>

;; raw/usearch_save <f>
(defn save
  "Saves `index` to a file at `path`."
  [index path]
  (with-error
    (raw/usearch_save index path)))
;; raw/usearch_save_buffer <f>

;; raw/usearch_search <f>
(defn search
  "Performs k-Approximate Nearest Neighbors (kANN) Search for closest vectors to query.

  Returns a map of the `n` closest matches. The keys are the keys and values are the distances.

  If no `n` is provided, returns the closest match."
  ([index vec]
   (search index vec 1))
  ([index vec n]
   (let [;; longs
         keys* (Memory. (* 8 n))
         ;; floats
         distances* (Memory. (* 4 n))]
     (let [found (with-error
                   (raw/usearch_search index vec (vector-kind vec) n keys* distances*))]
       (into {}
             (map (fn [i]
                    [(.getLong keys* (* 8 i))
                     (.getFloat distances* (* 4 i))]))
             (range found))))))

;; raw/usearch_serialized_length <f>
(defn serialized-length
  "Reports expected file size after serialization."
  [index]
  (with-error
    (raw/usearch_serialized_length index)))
;; raw/usearch_size <f>
(defn size
  "Number of indexed vectors."
  [index]
  (with-error
    (raw/usearch_size index)))

;; raw/usearch_view <f>
;; raw/usearch_view_buffer <f>




(comment
  (def index (init {:dimensions 10
                    ;; :metric :metric/cos
                    :quantization :quantization/f32}
                   (into {}
                         (map (fn [i]
                                [i (float-array (range 10))]))
                         (range 123))))

  (save index "foo.usearch")

  (def new-index
    (init {:dimensions 10
           ;; :metric :metric/cos
           :quantization :quantization/f32}))
  (load new-index "foo.usearch")
  (save new-index "asdadsf")

  (reserve index 10)

  (add index 31 (float-array 10))

  (reserve index 10)
  (add index 32 (float-array (range 10)))

  (get index 1)
  
  
  
  ,)
