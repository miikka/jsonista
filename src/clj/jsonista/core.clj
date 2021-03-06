(ns jsonista.core
  "JSON encoding and decoding based on Jackson Databind.

  Encoding example:

    (require '[jsonista.core :as json])
    (json/to-json {:hello 1})
    ;; => \"{\\\"hello\\\":1}\"

  Decoding example:

    (def +data+ (json/to-json {:foo \"bar\"}))
    (json/from-json +data+)
    ;; => {\"foo\" \"bar\"}

  CONFIGURATION

  You can configure encoding and decoding by creating a custom mapper object
  with jsonista.core/make-mapper. The options are passed in as a map.

  For example, to convert map keys into keywords while decoding:

    (json/from-json +data+ (json/make-mapper {:keywordize? true}))
    ;; => {:foo \"bar\"}

  See the docstring of jsonista.json/make-mapper for all available options.

  CUSTOM ENCODERS

  Custom encoder is a function that take a value and a JsonGenerator object as
  the parameters. The function should call JsonGenerator methods to emit the
  desired JSON. This is the same as how custom encoders work in Cheshire.

  Custom encoders are configured by the make-mapper option :encoders, which is a
  map from types to encoder functions.

  For example, to encode java.awt.Color:

     (let [encoders {java.awt.Color (fn [color gen] (.writeString gen (str color)))}
           mapper (json/make-mapper {:encoders encoders})]
       (json/to-json (java.awt.Color. 1 2 3) mapper))
     ;; => \"\\\"java.awt.Color[r=1,g=2,b=3]\\\"\"

  JSONISTA VS CHESHIRE

  jsonista uses Jackson Databind while Cheshire uses Jackson Core. In our
  benchmarks, jsonista performs better than Cheshire (take look at
  json_perf_test.clj). On the other hand, Cheshire has a wider set of features
  and has been used in production much more."
  (:import
    com.fasterxml.jackson.databind.ObjectMapper
    com.fasterxml.jackson.databind.module.SimpleModule
    com.fasterxml.jackson.databind.SerializationFeature
    com.fasterxml.jackson.core.JsonGenerator$Feature
    (jsonista.jackson
      DateSerializer
      FunctionalSerializer
      KeywordSerializer
      KeywordKeyDeserializer
      PersistentHashMapDeserializer
      PersistentVectorDeserializer
      SymbolSerializer
      RatioSerializer)
    (java.io InputStream Writer)))

(set! *warn-on-reflection* true)

(defn- make-clojure-module
  "Create a Jackson Databind module to support Clojure datastructures.

  See make-mapper docstring for the documentation of the options."
  [{:keys [keywordize? encoders date-format]}]
  (doto (SimpleModule. "Clojure")
    (.addDeserializer java.util.List (PersistentVectorDeserializer.))
    (.addDeserializer java.util.Map (PersistentHashMapDeserializer.))
    (.addSerializer clojure.lang.Keyword (KeywordSerializer. false))
    (.addSerializer clojure.lang.Ratio (RatioSerializer.))
    (.addSerializer clojure.lang.Symbol (SymbolSerializer.))
    (.addKeySerializer clojure.lang.Keyword (KeywordSerializer. true))
    (.addSerializer java.util.Date (if date-format
                                     (DateSerializer. date-format)
                                     (DateSerializer.)))
    (as-> module
        (doseq [[cls encoder-fn] encoders]
          (.addSerializer module cls (FunctionalSerializer. encoder-fn))))
    (cond->
        ;; This key deserializer decodes the map keys into Clojure keywords.
        keywordize? (.addKeyDeserializer Object (KeywordKeyDeserializer.)))))

(defn ^ObjectMapper make-mapper
  "Create an ObjectMapper with Clojure support.

  The optional first parameter is a map of options. The following options are
  available:
  Encoding options: 
  :pretty           -- set to true use Jacksons pretty-printing defaults
  :escape-non-ascii -- set to true to escape non ascii characters
  :date-format      -- string for custom date formatting. If not set will use default \"yyyy-MM-dd'T'HH:mm:ss'Z'\"
  :encoders     --  a map of custom encoders where keys should be types and values
                    should be encoder functions
  Encoder functions take two parameters: the value to be encoded and a
  JsonGenerator object. The function should call JsonGenerator methods to emit
  the desired JSON.
  Decoding options:
  :keywordize?  --  set to true to convert map keys into keywords (default: false)"
  ([] (make-mapper {}))
  ([options]
   (doto (ObjectMapper.)
     (.registerModule (make-clojure-module options))
     (cond-> (:pretty options) (.enable SerializationFeature/INDENT_OUTPUT)
             (:escape-non-ascii options) (.enable ^"[Lcom.fasterxml.jackson.core.JsonGenerator$Feature;"
                                                  (into-array [JsonGenerator$Feature/ESCAPE_NON_ASCII]))))))

(def ^ObjectMapper +default-mapper+
  "The default ObjectMapper instance used by jsonista.core/to-json and
  jsonista.core/from-json unless you pass in a custom one."
  (make-mapper {}))

(defn from-json
  "Decode a value from a JSON string or InputStream.

  To configure, pass in an ObjectMapper created with make-mapper, or pass in a map with options.
  See make-mapper docstring for available options"
  ([data] (from-json data +default-mapper+))
  ([data opts-or-mapper]
   (let [mapper (cond
                  (map? opts-or-mapper) (make-mapper opts-or-mapper)
                  (instance? ObjectMapper opts-or-mapper) opts-or-mapper)]
     (if (string? data)
       (.readValue ^ObjectMapper mapper ^String data ^Class Object)
       (.readValue ^ObjectMapper mapper ^InputStream data ^Class Object)))))

(defn ^String to-json
  "Encode a value as a JSON string.

  To configure, pass in an ObjectMapper created with make-mapper, or pass in a map with options.
  See make-mapper docstring for available options"
  ([object] (to-json object +default-mapper+))
  ([object opts-or-mapper]
   (let [mapper (cond
                  (map? opts-or-mapper) (make-mapper opts-or-mapper)
                  (instance? ObjectMapper opts-or-mapper) opts-or-mapper)]
     (.writeValueAsString ^ObjectMapper mapper object))))

(defn ^String write-to
  ([object ^Writer writer]
   (write-to object writer +default-mapper+))
  ([object ^Writer writer ^ObjectMapper mapper]
   (.writeValue mapper writer object)))
