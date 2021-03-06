(ns clojure.tools.analyzer.jvm.core-test
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.test :refer [deftest is]]))

(defprotocol p (f [_]))
(defn f1 [^long x])
(def e (ana.jvm/empty-env))

(defmacro ast [form]
  `(binding [ana/macroexpand-1 ana.jvm/macroexpand-1
             ana/create-var    ana.jvm/create-var
             ana/parse         ana.jvm/parse
             ana/var?          var?]
     (ana/analyze '~form e)))

(defmacro ast1 [form]
  `(binding [ana/macroexpand-1 ana.jvm/macroexpand-1
             ana/create-var    ana.jvm/create-var
             ana/parse         ana.jvm/parse
             ana/var?          var?]
     (ana.jvm/analyze '~form e)))

(defmacro mexpand [form]
  `(ana.jvm/macroexpand-1 '~form e))

(deftest macroexpander-test
  (is (= (list '. (list 'clojure.core/identity java.lang.Object) 'toString)
         (mexpand (.toString Object))))
  (is (= (list '. java.lang.Integer '(parseInt "2")) (mexpand (Integer/parseInt "2")))))

(deftest analyzer-test

  (let [v-ast (ast #'+)]
    (is (= :the-var (:op v-ast)))
    (is (= #'+ (:var v-ast))))

  (let [mn-ast (ast (monitor-enter 1))]
    (is (= :monitor-enter (:op mn-ast)))
    (is (= 1 (-> mn-ast :target :form))))

  (let [mx-ast (ast (monitor-exit 1))]
    (is (= :monitor-exit (:op mx-ast)))
    (is (= 1 (-> mx-ast :target :form))))

  (let [i-ast (ast (clojure.core/import* "java.lang.String"))]
    (is (= :import (:op i-ast)))
    (is (= "java.lang.String" (:class i-ast))))

  (let [r-ast (ast (reify
                     Object (toString [this] "")
                     Appendable (^Appendable append [this ^char x] this)))]
    (is (= :with-meta (-> r-ast :op))) ;; line/column info
    (is (= :reify (-> r-ast :expr :op)))
    (is (= #{Appendable clojure.lang.IObj} (-> r-ast :expr :interfaces)))
    (is (= '#{toString append} (->> r-ast :expr :methods (mapv :name) set))))

  (let [dt-ast (ast (deftype* x user.x [a b]
                      :implements [Appendable]
                      (^Appendable append [this ^char x] this)))]
    (is (= :deftype (-> dt-ast :op)))
    (is (= '[a b] (->> dt-ast :fields (mapv :name))))
    (is (= '[append] (->> dt-ast :methods (mapv :name))))
    (is (= 'user.x (-> dt-ast :class-name))))

  (let [c-ast (ast (case* 1 0 0 :number {2 [2 :two] 3 [3 :three]} :compact :int))]
    (is (= :number (-> c-ast :default :form)))
    (is (= #{2 3} (->> c-ast :tests (mapv (comp :form :test)) set)))
    (is (= #{:three :two} (->> c-ast :thens (mapv (comp :form :then)) set)))
    (is (= 3 (-> c-ast :high)))
    (is (= :int (-> c-ast :test-type)))
    (is (= :compact (-> c-ast :switch-type)))
    (is (= 2 (-> c-ast :low)))
    (is (= 0 (-> c-ast :shift)))
    (is (= 0 (-> c-ast :mask))))

  (is (= Throwable (-> (ast (try (catch :default e))) :catches first :class))))

(deftest doseq-chunk-hint
  (let [tree (ast1 (doseq [item (range 10)]
                     (println item)))
        {[_ chunk] :bindings} tree]
    (is (= :loop (:op tree)))
    (is (.startsWith (name (:name chunk)) "chunk"))
    (is (= clojure.lang.IChunk (:tag chunk)))))
