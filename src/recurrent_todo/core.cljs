(ns recurrent-todo.core
  (:require
    garden.core
    recurrent.drivers.dom
    [dommy.core :as dommy :include-macros true]
    [recurrent.core :as recurrent :include-macros true]
    [ulmus.core :as ulmus]))

;; ## Recurrent
;; Recurrent is a way to create functional-reactive GUIs in Clojurescript.  It's designed to be reactive all-the-way-down (unlike React which is only reactive at the point of rendering).  It hopefully also avoids some of the boilerplate of Redux while maintaing functions as encapsulated units (rather than functions + constants + actions + reducers).

;; ## The Component
;; A component in Recurrent is a function of two arguments.  The first argument, called `props`, is a mapping of keys to static data.  A second argument, called `sources`, is a mapping of keys to reactive (time-varying) signals created with Ulmus.  Said Components (read functions) return a map of keyed signals that can be used to update other components, or mutate some external state.  Recurrent provides faculties for taking a signal of virtual-dom and diffing that into the actual DOM.
;; While Recurrent components are functions, it provides a macro to make building them a little more natural.  Let's look at that now.
;;

;; # An Example (Text Input)
;; We're going to start by creating a text input component to see what that might look like in the Recurrent model.

(recurrent/defcomponent TextInput
  ;
  ; Like `defn` we take arguments as a list. In this case, we define the 
  ; keys that are expected in the `props` and `sources` objects, respectively.
  ; 
  ; The first key in the sources list (here called `dom-$`) is treated
  ; specially.  As a source it provides access to a DOM utility that
  ; can be used to create signals from dom events before the actual DOM
  ; is rendered. Our first returned signal must match this name and will
  ; represent the virtual dom of this component over time.
  ;
  [:initial-value] [:dom-$]

  ;
  ; We can provide a constructor to generate any additional data that will
  ; be useful during the lifetime of this component.  It's a function that 
  ; gets passed the props and sources and returns a map hitherto passed to
  ; functions as the `this` argument.
  ;
  ; In this case, we don't need this.
  ; 
  (fn [])

  ;
  ; We have a chance to define some CSS now that will apply to this component.
  ; This is provided in the Garden format.  It is included in the html page
  ; globally, so be careful, but will only ever get included once.
  ; 
  [:.text-input {:margin "16px 0px 0px 16px"}]

  ;
  ; Finally we provide a list of return signals, keyed appropriately.  The 
  ; first signal returned must dereference a hiccup style form of virtual dom.
  ; The name of this key must match the name provided as the first key in the 
  ; sources list above.
  ;
  ; The value of any of the returned keys is a function that takes four arguments:
  ; 1. `this` - the value returned from the constructor
  ; 2. `props` - the value passed in as props
  ; 3. `sources` - the value passed in as sources
  ; 4. `sinks` - a map including this key, and all others, returned from this component,
  ; with associated signals.
  ;
  ; The function must return a signal.
  ;
  :dom-$ (fn [_ props sources sinks]
           ;
           ; Here we can return a hiccup form that will be rendered into the dom.
           ; This is usually done by reducing over the events associated with the component.
           ; These can be returned as a signal by querying the DOM utility provided as a source
           ; under the key matching the first listed source above (:dom-$ in this case).
           ; 
           ; For this particular component, we're interested in the text box's value which is a reduction 
           ; over keypress events.  We're going to grab this out of sinks and will implement it
           ; in a second.
           ;
           (ulmus/map 
             (fn [value]
               ; Heres our html.
               [:input {:class "text-input" :type "text" :value value :placeholder "What needs to be done?"}])
             (ulmus/start-with!
               ; We set the starting value to the initial-value passed in props
               (or (:initial-value props) "")
               ; We can access any of the defined sinks even before they're declared.
               (:value-$ sinks))))

  ;
  ; Moving right along, let's look at how we define the value-$ sink.
  ;
  :value-$ (fn [_ props sources sinks]
             ;
             ; We use our dom utility here to look at keydown events
             ; the utility is a function that takes a css selector and a dom event
             ; it returns a signal containing those events over time.
             ; 
             (ulmus/map
               (fn [evt]
                 (if (= (.-keyCode evt) 13) 
                   ""
                   (.-value (.-target evt))))
               ((:dom-$ sources) "input" "keydown"))) 

  :value-submission-$ (fn [_ _ sources sinks]
                        ;
                        ; We also want some way to keep track of when the user presses enter
                        ; and thus commits a todo into the todo list. We're going to sample
                        ; the value-$ sink when the user presses enter.
                        ;
                        (ulmus/sample-on
                          (:value-$ sinks)
                          (ulmus/filter
                            (fn [evt]
                              (= (.-keyCode evt) 13))
                            ((:dom-$ sources) "input" "keydown")))))

;; # A More Complex Example
;; Let's look at something a little more complex.  The Todo component
;; has a reasonable amount of internal state management so it makes 
;; a useful case study.  We give the Todo an initial value 
;; through it's props, but double clicking the Todo displays a text
;; input through which we can update the value.  Clicking to the left of the 
;; todo marks it as done.  These will be exposed as sinks, as well as
;; consumed by our render signal.
;;
(recurrent/defcomponent Todo
 
  ;
  ; We need to take an initial value here, but the
  ; user can also update it by editing the todo.
  ; 
  [:initial-value] [:dom-$]

  ;
  ; Constructor
  ;
  ; It's helpful for Todo's to have a unique id for their lifetime.
  ; We generate this in the constructor and can access it hereafter
  ; on `this`.
  ;
  (fn []
    {:id (gensym)})

  ;
  ; We include some styles here.
  ;
  [:.todo {:cursor "pointer"
           :display "flex"
           :margin "16px"
           :user-select "none"}
   [:.check {:border "1px solid lightgrey"
             :border-radius "8px"
             :height "16px"
             :margin-right "8px"
             :width "16px"}
    [:&.done {:background "black"}]]
   [:.close {:margin-left "8px"}]
   [:li {:display "block"}
    [:&.done {:text-decoration "line-through"}]]]

  ;
  ; Our render signal is based on three sinks, the current value,
  ; whether or not the todo is being edited, and whether or not the todo
  ; is marked as complete.  ulmus.core/latest is used to grab the most
  ; current value from each of these signals.  These then get mapped over
  ; to create a suitable block of vdom.
  ;
  ; Like React, we give keys to lists items to assist reconciliation.
  ; We do this here by attaching the :hipo/key metadata.
  ;
  :dom-$ (fn [this _ sources sinks]
           (ulmus/map
             (fn [[value editing? done?]]
               ^{:hipo/key (:id this)}
               [:div {:class "todo"}
                 [:div {:class (str "check " (if done? "done"))}]
                 (if editing?
                   [:input {:type "text"
                            :value value}]
                   [:li {:class (if done? "done" "")} value])
                [:div {:class "close" :data-todo value} "[ x ]"]])
             (ulmus/latest
               (:value-$ sinks)
               (:editing?-$ sinks)
               (:done?-$ sinks))))

  ;
  ; We can grab the value in a similar way to how it was done with the TextInput.
  ; Interestingly, though the <input> is only rendered conditionally, we can 
  ; attach the listener here through a query selector and expect Recurrent to do
  ; the right thing.
  ;
  :value-$ (fn [_ props sources _]
             (ulmus/start-with!
               (:initial-value props)
               (ulmus/map
                 (fn [evt]
                   (.-value (.-target evt)))
                 ((:dom-$ sources) "input" "keydown"))))

  ; 
  ; Here's where we start to see some of the real benifit of this application architecture.
  ; Instead of a mess of imperitive code (or a cascade of actions, constants, reducers,
  ; and a big 'ol pile of state), we can write simple functional snippets that are predictable and easy to
  ; grok.  Here we simply reduce over the not function, starting with false, whenever the user 
  ; double clicks the todo, or presses enter of the input box.  
  ; 
  :editing?-$ (fn [_ _ sources _]
                (ulmus/reduce
                  not
                  false
                  (ulmus/merge
                    (ulmus/filter #(= (.-keyCode %) 13)
                                  ((:dom-$ sources) "input" "keydown"))
                    ((:dom-$ sources) "li" "dblclick"))))
  ;
  ; Similarly to the above.
  ;
  :done?-$ (fn [_ _ sources _]
             (ulmus/reduce
               not
               false
               ((:dom-$ sources) ".check" "click"))))

;; # The Todo List
(recurrent/defcomponent TodoList
  ;
  ; In this case we pass the value-submission-$ of our
  ; input in as a source.
  ;
  ; We don't take any props here.
  ;
  [] [:dom-$ :value-submission-$]

  ;
  ; Constructor
  ;
  (fn [])

  ;
  ; CSS
  ;
  [:.todo-list {}]

  ;
  ; sinks
  ;
  :dom-$ (fn [_ _ _ sinks]
           ;
           ; Our html is generated as a flat-map over the todo-list-$ sink.
           ; flat-map allows us to return a secondary signal from a primary signal
           ; and operate on the values therefrom instead.
           (ulmus/flat-map
             ;
             ; We start with the todo-list generated below.  The elements of
             ; todo-list are the Todo components constructed in the todo-list-$ sink.
             ; in the first func here, we dig into each component, pulling out the 
             ; latest value of dom-$.
             (fn [todo-list]
               (apply ulmus/latest (map :dom-$ todo-list)))
             ;
             ; Once we have that, it's easy to render them into an ordered list.
             (fn [todo-list-dom]

               ; Note the use of syntax quote here, handy for expanding a list
               ; directly into the markup.
               `[ol {:class "todo-list"} ~@todo-list-dom])

             (:todo-list-$ sinks)))

  :todo-list-$ (fn [_ _ sources _]
                 ; Finally our todo list.  It's a reduction over two sources.
                 ; 1. value-submission-$, coming from the text input defined above.
                 ; 2. A signal of click events on the list items close buttons.
                 ; These are used to add or remove todo's respectively.
                 (ulmus/reduce
                   (fn [todo-list change]
                     (if (= (:type change) :create) 
                       ;
                       ; We construct a new Todo for each request that comes in.
                       ;
                       (conj todo-list (Todo 
                                         {:initial-value (:todo change)}
                                         {:dom-$ (:global-dom-$ sources)}))
                       (remove #(= @(:value-$ %) (:todo change)) todo-list)))
                   [] 
                   (ulmus/merge
                     (ulmus/map
                       (fn [todo]
                         {:type :create
                          :todo todo})
                       (:value-submission-$ sources))
                     (ulmus/map
                       (fn [e]
                         {:type :delete
                          :todo (dommy/attr (.-target e) "data-todo")})
                       ((:dom-$ sources) ".close" "click"))))))

;; # Main
;; Our main component, defined as a function, will construct the page
;; by creating our components and taking their latest values.
(defn Main
  ;
  ; Main is a component just like the others - we're defining it without he macro sugar.
  ;
  [props sources] 
  ;
  ; We construct our components, again, they're functions of props and sources.
  ;
  (let [todo-input (TextInput {:initial-value ""}
                              {:dom-$ (:dom-$ sources)})
        todo-list (TodoList {} {:dom-$ (:dom-$ sources)
                                :value-submission-$ (:value-submission-$ todo-input)})]
    ;
    ; This must match the key provided below in `recurrent.core/run!`.  `run!` takes care
    ; of providing the dom utility to the component, and watching the returned html
    ; for diffing into the actual dom.
    ;
    {:dom-$
      ;
      ; We want to map over the latest version of each to gernerate our dom.
      ;
      (ulmus/map
        (fn [[todo-input-dom todo-list-dom]]
          [:main
           todo-input-dom
           todo-list-dom])
        (ulmus/latest
          (:dom-$ todo-input)
          (:dom-$ todo-list)))}))

;; recurrent.core/run! wires everything together.
(defn main!
  []
  (recurrent/run!
    Main
    {:dom-$ (recurrent.drivers.dom/from-id "app")}))

