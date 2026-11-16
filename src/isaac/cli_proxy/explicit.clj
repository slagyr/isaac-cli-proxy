(ns isaac.cli-proxy.explicit
  "Global --remote entry point for server-only commands. The forwarder handles
   credential resolution and transports the original command argv."
  (:require
    [clojure.string :as str]
    [isaac.cli-proxy.forwarder :as forwarder]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.registry :as registry]))

(defmethod cli-api/run :--remote [_ {:keys [_raw-args root]}]
  (let [[url & argv] _raw-args
        command (registry/get-command (first argv))]
    (if (and (not (str/blank? url)) (:server-only command))
      (:exit ((forwarder/make-forwarder) {:root root :argv (into ["--remote" url] argv)}))
      (do
        (binding [*out* *err*]
          (println "--remote requires a URL and a server-only command"))
        2))))
