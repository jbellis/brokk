; We capture the whole definition node now (@*.definition) for top-level items.
; The name is still useful (@*.name).
; Nested items (methods, fields) will be found by traversing the definition node's children in Java code.

; Top-level Class definition
(class_definition
  name: (identifier) @class.name) @class.definition

; Top-level Function definition
(function_definition
  name: (identifier) @function.name) @function.definition

; Capture field assignments like `self.x = ...` to help identify them during skeleton building.
; We need the helper `obj` capture for the predicate.
(assignment
  left: (attribute
    object: (identifier) @obj (#eq? @obj "self")
    attribute: (identifier) @field.name)) @field.declaration
