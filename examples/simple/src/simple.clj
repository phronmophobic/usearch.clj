(ns simple
  (:require [com.phronemophobic.usearch :as usearch])
  (:import java.util.Random))

(def random (Random. 42))

(defn rand-vec [dim]
  (float-array
   (eduction
    (map (fn [_]
           (.nextFloat random)))
    (range dim))))

(def dim 512)

;; Generate 100 sample vectors
;; integer keys -> vector.
(def vecs
  (into {}
        (map (fn [i]
               [i (rand-vec dim)]))
        (range 100)))


(def index
  (usearch/init {:dimensions dim
                 :quantization :quantization/f32}
                ;; vectors to load
                vecs))

(count index)
;; 100

;; Can check for the presence of keys
(every? #(contains? index %)
        (keys vecs))
;; true

;; need to reserve more space before adding more vecs
(usearch/reserve index
                 (+ (usearch/capacity index)
                    1))
(usearch/add index 101 (rand-vec dim))

(count index)
;; 101

;; get closest match
(usearch/search index
                (float-array (range dim)))

;; returns [key, distance]
;; [16 -71872.88] 

;; lookup vector for 16
(get index 16)
;; #object["[F" 0x4297c486 "[F@4297c486"]

;; get 10 closest matches
(usearch/search index
                (float-array (range dim))
                10)
;; [[16 -71872.88] [10 -70801.016] [37 -70298.38] [49 -69604.12] [55 -69434.65] [78 -69195.81] [59 -69186.44] [28 -69116.14] [9 -68563.03] [24 -68498.59]]

;; Save index to disk
(usearch/save index "simple.usearch")


;; Load index from disk
(def index-from-disk
  (usearch/init {:dimensions dim
                 :quantization :quantization/f32}))
(usearch/load index-from-disk "simple.usearch")

(count index-from-disk)
;; 101






