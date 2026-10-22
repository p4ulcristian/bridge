(ns mailroom.answers
  "What mailroom answers instead of data, one definition for both sides:

     {:error \"Not found.\"}                      ; a refusal: a step's, or mailroom's own
                                                 ; (\"Too many requests.\", \"Something went wrong.\")
     {:errors {:shop/id [\"should be an id\"]}}   ; params that didn't fit their shape (the server's)
     {:mailroom/errors [\"…\"] :data …}           ; an answer that didn't fit its shape (the browser's)

   The first two are the whole map; the third carries the data as it came.")

(defn is-refusal?
  "{:error …} and nothing else: a refusal. The server sends it as it is,
   past the :answer-shape."
  [answer]
  (and (map? answer) (= #{:error} (set (keys answer)))))

(defn is-error-answer?
  "Whether an answer is one of mailroom's error answers rather than data:
   a refusal, {:error …} alone; {:errors …} alone, params that didn't fit;
   or one carrying :mailroom/errors, an answer that didn't fit its shape."
  [answer]
  (and (map? answer)
       (or (is-refusal? answer)
           (= #{:errors} (set (keys answer)))
           (contains? answer :mailroom/errors))))
