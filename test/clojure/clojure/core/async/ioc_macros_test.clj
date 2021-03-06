(ns clojure.core.async.ioc-macros-test
  (:require [clojure.core.async.impl.ioc-macros :as ioc]
            [clojure.core.async :refer :all :as async]
            [clojure.test :refer :all]))

(defn runner-wrapper
  "Simple wrapper that runs the state machine to completion"
  [f]
  (loop [state (f)]
    (if (ioc/finished? state)
      (ioc/aget-object state ioc/VALUE-IDX)
      (recur (f state)))))

(defmacro runner
  "Creates a runner block. The code inside the body of this macro will be translated
  into a state machine. At run time the body will be run as normal. This transform is
  only really useful for testing."
  [& body]
  (binding [ioc/*symbol-translations* '{pause clojure.core.async.ioc-macros/pause
                                        case case}
            ioc/*local-env* &env]
    `(runner-wrapper ~(ioc/state-machine body 0))))

(deftest runner-tests
  (testing "do blocks"
    (is (= 42
           (runner (do (pause 42)))))
    (is (= 42
           (runner (do (pause 44)
                       (pause 42))))))
  (testing "if expressions"
    (is (= true
           (runner (if (pause true)
                     (pause true)
                     (pause false)))))
    (is (= false
           (runner (if (pause false)
                     (pause true)
                     (pause false)))))
    (is (= true
           (runner (when (pause true)
                     (pause true)))))
    (is (= nil
           (runner (when (pause false)
                     (pause true))))))
  
  (testing "loop expressions"
    (is (= 100
           (runner (loop [x 0]
                     (if (< x 100)
                       (recur (inc (pause x)))
                       (pause x)))))))
  
  (testing "let expressions"
    (is (= 3
           (runner (let [x 1 y 2]
                     (+ x y))))))
  
  (testing "vector destructuring"
    (is (= 3
           (runner (let [[x y] [1 2]]
                     (+ x y))))))

  (testing "hash-map destructuring"
    (is (= 3
           (runner (let [{:keys [x y] x2 :x y2 :y :as foo} {:x 1 :y 2}]
                     (assert (and foo (pause x) y x2 y2 foo))
                     (+ x y))))))
  
  (testing "hash-map literals"
    (is (= {:1 1 :2 2 :3 3}
           (runner {:1 (pause 1)
                    :2 (pause 2)
                    :3 (pause 3)}))))
  (testing "hash-set literals"
    (is (= #{1 2 3}
           (runner #{(pause 1)
                     (pause 2)
                     (pause 3)}))))
  (testing "vector literals"
    (is (= [1 2 3]
           (runner [(pause 1)
                    (pause 2)
                    (pause 3)]))))
  (testing "dotimes"
    (is (= 42 (runner
               (dotimes [x 10]
                 (pause x))
               42))))
  
  (testing "fn closures"
    (is (= 42
           (runner
            (let [x 42
                  _ (pause x)
                  f (fn [] x)]
              (f))))))

  (testing "case"
    (is (= 43
           (runner
            (let [value :bar]
              (case value
                :foo (pause 42)
                :bar (pause 43)
                :baz (pause 44))))))
    (is (= :default
           (runner
            (case :baz
              :foo 44
              :default)))))

  (testing "try"
    (is (= 42
           (runner
            (try 42
                 (catch Throwable ex ex)))))
    (is (= 42
           (runner
            (try
              (assert false)
              (catch Throwable ex 42)))))

    (let [a (atom false)
          v (runner
             (try
               true
               (catch Throwable ex false)
               (finally (pause (reset! a true)))))]
      (is (and @a v)))

    (let [a (atom false)
          v (runner
             (try
               (assert false)
               (catch Throwable ex true)
               (finally (reset! a true))))]
      (is (and @a v)))))



(defn identity-chan 
  "Defines a channel that instantly writes the given value"
  [x]
  (let [c (chan 1)]
    (>!! c x)
    (close! c)
    c))

(deftest async-test
  (testing "values are returned correctly"
    (is (= 10
           (<!! (go (<! (identity-chan 10)))))))
  (testing "writes work"
    (is (= 11
           (<!! (go (let [c (chan 1)]
                      (>! c (<! (identity-chan 11)))
                      (<! c)))))))

  (testing "case with go"
    (is (= :1
           (<!! (go (case (name :1)
                      "0" :0
                      "1" :1
                      :3))))))

  (testing "nil result of go"
    (is (= nil
           (<!! (go nil)))))

  (testing "can get from a catch"
    (let [c (identity-chan 42)]
      (is (= 42
             (<!! (go (try
                        (assert false)
                        (catch Throwable ex (<! c))))))))))

(deftest enqueued-chan-ops
  (testing "enqueued channel puts re-enter async properly"
    (is (= [:foo 42]
           (let [c (chan)
                 result-chan (go (>! c :foo) 42)]
             [(<!! c) (<!! result-chan)]))))
  (testing "enqueued channel takes re-enter async properly"
    (is (= :foo
           (let [c (chan)
                 async-chan (go (<! c))]
             (>!! c :foo)
             (<!! async-chan)))))
  (testing "puts into channels with full buffers re-enter async properly"
    (is (= #{:foo :bar :baz :boz}
           (let [c (chan 1)
                 async-chan (go
                             (>! c :foo)
                             (>! c :bar)
                             (>! c :baz)

                             (>! c :boz)
                             (<! c))]
             (set [(<!! c)
                   (<!! c)
                   (<!! c)
                   (<!! async-chan)]))))))

(defn rand-timeout [x]
  (timeout (rand-int x)))

(deftest alt-tests
  (testing "alts works at all"
    (let [c (identity-chan 42)]
      (is (= [42 c]
             (<!! (go (alts!
                       [c])))))))
  (testing "alt works"
    (is (= [42 :foo]
           (<!! (go (alt!
                     (identity-chan 42) ([v] [v :foo])))))))

  (testing "alts can use default"
    (is (= [42 :default]
           (<!! (go (alts!
                     [(chan 1)] :default 42))))))

  (testing "alt can use default"
             (is (= 42
                    (<!! (go (alt!
                              (chan) ([v] :failed)
                              :default 42))))))

  (testing "alt obeys its random-array initialization"
    (is (= #{:two}
           (with-redefs [clojure.core.async/random-array
                         (constantly (int-array [1 2 0]))]
             (<!! (go (loop [acc #{}
                             cnt 0]
                        (if (< cnt 10)
                          (let [label (alt!
                                        (identity-chan :one) ([v] v)
                                        (identity-chan :two) ([v] v)
                                        (identity-chan :three) ([v] v))]
                            (recur (conj acc label) (inc cnt))))
                        acc))))))))

(deftest resolution-tests
  (let [<! (constantly 42)]
    (is (= 42 (<!! (go (<! (identity-chan 0)))))
        "symbol translations do not apply to locals outside go"))

  (is (= 42 (<!! (go (let [<! (constantly 42)]
                       (<! (identity-chan 0))))))
      "symbol translations do not apply to locals inside go")

  (let [for vector x 3]
    (is (= [[3 [0 1]] 3]
           (<!! (go (for [x (range 2)] x))))
        "locals outside go are protected from macroexpansion"))

  (is (= [[3 [0 1]] 3]
         (<!! (go (let [for vector x 3]
                    (for [x (range 2)] x)))))
      "locals inside go are protected from macroexpansion")

  (let [c (identity-chan 42)]
    (is (= [42 c] (<!! (go (async/alts! [c]))))
        "symbol translations apply to resolved symbols")))
