(ns polylith.clj.core.test-runner.core
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [polylith.clj.core.deps.interface :as deps]
            [polylith.clj.core.common.interface :as common]
            [polylith.clj.core.util.interface.color :as color]
            [polylith.clj.core.util.interface.str :as str-util]
            [polylith.clj.core.util.interface.time :as time-util]
            [polylith.clj.core.validator.interface :as validator])
  (:refer-clojure :exclude [test]))

(defn ->test-statement [ns-name]
  (let [ns-symbol (symbol ns-name)]
    `(do (use 'clojure.test)
         (require '~ns-symbol)
         (clojure.test/run-tests '~ns-symbol))))

(defn resolve-deps [{:keys [name] :as project} is-verbose color-mode]
  (try
    (into #{} (mapcat #(-> % second :paths)
                      (deps/resolve-deps project is-verbose)))
    (catch Exception e
      (println (str "Couldn't resolve libraries for the " (color/project name color-mode) " project: " e))
      (throw e))))

(defn brick-test-namespaces [bricks test-brick-names]
  (let [brick-name->namespaces (into {} (map (juxt :name #(-> % :namespaces :test)) bricks))]
    (mapv :namespace (mapcat brick-name->namespaces test-brick-names))))

(defn project-test-namespaces [project-name projects-to-test namespaces]
  (when (contains? (set projects-to-test) project-name)
    (map :namespace (:test namespaces))))

(defn execute-fn [function fn-type project-name class-loader color-mode]
  (if function
    (do
      (println (str "Running test " fn-type " for the " (color/project project-name color-mode)
                    " project: " function))
      (try
        (if (= :missing-fn
               (common/eval-in class-loader
                               `(if-let [~'fun (clojure.core/requiring-resolve '~function)]
                                  (~'fun '~project-name)
                                  :missing-fn)))
          (do
            (println (color/error color-mode (str "Could not find " fn-type " function: " function)))
            false)
          (do
            (println)
            true))
        (catch Throwable t
          (let [message (str (or (some-> t .getCause) (.getMessage t)))]
            (println (color/error color-mode (str "\nTest " fn-type " failed: " message)))
            false))))
    true))

(defn run-test-statements [project-name class-loader test-statements run-message is-verbose color-mode]
  (println (str run-message))
  (when is-verbose (println (str "# test-statements:\n" test-statements) "\n"))

  (doseq [statement test-statements]
    (let [{:keys [error fail pass]}
          (try
            (common/eval-in class-loader statement)
            (catch Exception e
              (.printStackTrace e)
              (println (str (color/error color-mode "Couldn't run test statement") " for the " (color/project project-name color-mode) " project: " statement " " (color/error color-mode e)))))
          result-str (str "Test results: " pass " passes, " fail " failures, " error " errors.")]
      (when (or (nil? error)
                (< 0 error)
                (< 0 fail))
        (throw (Exception. (str "\n" (color/error color-mode result-str)))))
      (println (str "\n" (color/ok color-mode result-str))))))

(defn components-msg [component-names color-mode]
  (when (seq component-names)
    [(color/component (str/join ", " component-names) color-mode)]))

(defn bases-msg [base-names color-mode]
  (when (seq base-names)
    [(color/base (str/join ", " base-names) color-mode)]))

(defn run-message [project-name components bases bricks-to-test projects-to-test color-mode]
  (let [component-names (set (map :name components))
        base-names (set (map :name bases))
        bases-to-test (filter #(contains? base-names %) bricks-to-test)
        bases-to-test-msg (bases-msg bases-to-test color-mode)
        components-to-test (filter #(contains? component-names %) bricks-to-test)
        components-to-test-msg (components-msg components-to-test color-mode)
        projects-to-test-msg (when (-> projects-to-test empty? not) [(color/project (str/join ", " projects-to-test) color-mode)])
        entities-msg (str/join ", " (concat components-to-test-msg
                                            bases-to-test-msg
                                            projects-to-test-msg))
        project-cnt (count projects-to-test)
        bricks-cnt (count bricks-to-test)
        project-msg (if (zero? project-cnt)
                      ""
                      (str " and " (str-util/count-things "project" project-cnt)))]
    (str "Running tests from the " (color/project project-name color-mode) " project, including "
         (str-util/count-things "brick" bricks-cnt) project-msg ": " entities-msg)))

(defn run-tests-for-project [{:keys [bases components]}
                             {:keys [name paths namespaces] :as project}
                             {:keys [project-to-bricks-to-test project-to-projects-to-test]}
                             {:keys [setup-fn teardown-fn]}
                             is-verbose color-mode]
  (when (-> paths :test empty? not)
    (let [lib-paths (resolve-deps project is-verbose color-mode)
          all-paths (set (concat (:src paths) (:test paths) lib-paths))
          bricks (concat components bases)
          bricks-to-test (project-to-bricks-to-test name)
          projects-to-test (project-to-projects-to-test name)
          run-message (run-message name components bases bricks-to-test projects-to-test color-mode)
          brick-test-namespaces (brick-test-namespaces bricks bricks-to-test)
          project-test-namespaces (project-test-namespaces name projects-to-test namespaces)
          test-statements (mapv ->test-statement (concat brick-test-namespaces project-test-namespaces))
          class-loader (common/create-class-loader all-paths color-mode)]
      (when is-verbose (println (str "# paths:\n" all-paths "\n")))
      (if (-> test-statements empty?)
        (println (str "No tests to run for the " (color/project name color-mode) " project."))
        (when (execute-fn setup-fn "setup" name class-loader color-mode)
          (try
            (run-test-statements name class-loader test-statements run-message is-verbose color-mode)
            (finally
              (execute-fn teardown-fn "teardown" name class-loader color-mode))))))))

(defn has-tests-to-run? [{:keys [name]} {:keys [project-to-bricks-to-test project-to-projects-to-test]}]
  (not (empty? (concat (project-to-bricks-to-test name)
                       (project-to-projects-to-test name)))))

(defn print-no-tests-to-run-if-only-dev-exists [projects]
  (when (= 1 (count projects))
   (println "  No tests to run. To run tests for 'dev', type: poly test :dev")))

(defn print-projects-to-test [projects-to-test color-mode]
  (let [projects (str/join ", " (map #(color/project (:name %) color-mode)
                                     projects-to-test))]
    (println (str "Projects to run tests from: " projects))))

(defn print-execution-time [start-time]
  (println)
  (time-util/print-execution-time start-time))

(defn print-bricks-to-test [component-names base-names bricks-to-test color-mode]
  (when bricks-to-test
    (let [components-to-test (sort (set/intersection component-names (set bricks-to-test)))
          bases-to-test (sort (set/intersection base-names (set bricks-to-test)))
          components (components-msg components-to-test color-mode)
          bases (bases-msg bases-to-test color-mode)
          bricks (str/join ", " (concat components bases))]
      (println (str "Bricks to run tests for: " bricks)))))

(defn run [{:keys [components bases projects changes settings messages] :as workspace} is-verbose color-mode]
  (if (validator/has-errors? messages)
    (validator/print-messages workspace)
    (let [start-time (time-util/current-time)
          projects-to-test (sort-by :name (filter #(has-tests-to-run? % changes) projects))
          bricks-to-test (-> workspace :user-input :selected-bricks)
          component-names (set (map :name components))
          base-names (set (map :name bases))]
      (if (empty? projects-to-test)
        (print-no-tests-to-run-if-only-dev-exists projects)
        (do
          (print-projects-to-test projects-to-test color-mode)
          (print-bricks-to-test component-names base-names bricks-to-test color-mode)
          (println)
          (doseq [{:keys [name] :as project} projects-to-test]
            (let [test-settings (get-in settings [:projects name :test])]
              (run-tests-for-project workspace project changes test-settings is-verbose color-mode)))))
      (print-execution-time start-time))))
