(ns automat.compiler.base
  (:refer-clojure :exclude [compile])
  (:require
   [automat.compiler.core :as core :refer #?(:clj []
                                             :cljs [ICompiledAutomaton CompiledAutomatonState])]
   [automat.fsm :as fsm]
   [automat.stream :as stream])
  #?(:clj (:import
           [automat.compiler.core
            ICompiledAutomaton
            CompiledAutomatonState])))

(def is-identical? #?(:clj identical? :cljs keyword-identical?))

(defn- advance [fsm state stream signal reducers restart?]
  (let [signal                                 #(if (is-identical? % ::eof) % (signal %))
        ^CompiledAutomatonState original-state state
        stream                                 (stream/to-stream stream)
        original-stream-index                  (.-stream-index original-state)]
    (loop [original-input (stream/next-input stream ::eof)
           value          (.-value original-state)
           state          (.-state-index original-state)
           start-index    (.-start-index original-state)
           stream-index   original-stream-index]

      (let [input (signal original-input)]
        (if (is-identical? ::eof input)

          (if (== original-stream-index stream-index)
            original-state
            (CompiledAutomatonState.
             (contains? (:accept fsm) state)
             nil
             state
             start-index
             stream-index
             value))

          ;; Juji change to address #44
          ;; TODO: apply the same change to :eval compiler
          (let [input'        (if-let [candidates (:signal-candidates input)]
                                (some candidates (keys (get-in fsm [:state->input->state state])))
                                input)
                state''       (get-in fsm [:state->input->state state input'])
                state'        (or state'' (get-in fsm [:state->input->state state fsm/default]))
                ;; Juji change to skip :skip-token
                state'        (when (not= input' :skip-token) state')
                default?      (not (identical? state'' state'))
                value'        (if state'
                                (->> (concat
                                      (get-in fsm [:state->input->actions state fsm/pre])
                                      (when-not default?
                                        (get-in fsm [:state->input->actions state input']))
                                      (when default?
                                        (get-in fsm [:state->input->actions state fsm/default])))
                                     distinct
                                     (map reducers)
                                     (remove nil?)
                                     ;; Juji change to add :stream-index into value
                                     (reduce #(%2 %1 original-input)
                                             (if (vector? value)
                                               value
                                               (assoc value :stream-index (inc stream-index)))))
                                value)
                stream-index' (if (= state 0)
                                (inc stream-index)
                                stream-index)]

            (cond
              (or (nil? state') (is-identical? fsm/reject state'))
              (if restart?
                (recur
                 (if (= state 0)
                   (stream/next-input stream ::eof)
                   original-input)
                 value'
                 0
                 stream-index'
                 stream-index')
                ::reject)

              (contains? (:accept fsm) state')
              (CompiledAutomatonState.
               true
               nil
               state'
               start-index
               (inc stream-index)
               value')

              :else
              (recur
               (stream/next-input stream ::eof)
               value'
               (long state')
               start-index
               (inc stream-index)))))))))

(defn compile
  [fsm {:keys [reducers signal action-comparator]
        :or   {signal identity}}]
  {:pre [(core/precompiled-automaton? fsm)]}
  (let [initially-accepted? (contains? (:accept fsm) 0)]
    (with-meta
      (reify ICompiledAutomaton
        (start [_ initial-value]
          (CompiledAutomatonState.
           initially-accepted?
           nil
           0
           0
           0
           initial-value))
        (find [this state stream]
          (let [state (core/->automaton-state this state)]
            (if (.-accepted? ^CompiledAutomatonState state)
              state
              (advance fsm state stream signal reducers true))))
        (advance-stream [this state stream reject-value]
          (let [state  (core/->automaton-state this state)
                state' (advance fsm state stream signal reducers false)]
            (if (is-identical? ::reject state')
              reject-value
              state'))))
      {:fsm (-> fsm meta :fsm)})))
