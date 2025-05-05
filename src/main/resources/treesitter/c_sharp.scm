; Namespace declaration (Capture the whole node if needed later)
; (namespace_declaration) @namespace.declaration

; Using directives (Capture the whole node if needed later)
; (using_directive) @using.directive

; Class: Capture node and its name field
(class_declaration
  name: (identifier) @class.name) @class.declaration

; Interface: Capture node and its name field
(interface_declaration
  name: (identifier) @interface.name) @interface.declaration

; Method: Capture node and its name field
(method_declaration
  name: (identifier) @method.name) @method.declaration

; Field: Capture the field.declaration node and the identifier within variable_declarator's name field
(field_declaration
  (variable_declaration
    (variable_declarator
       name: (identifier) @field.name))) @field.declaration

; Property: Capture node and its name field
(property_declaration
  name: (identifier) @property.name) @property.declaration

; Constructor: Capture the whole node (name extraction handled in Java)
(constructor_declaration) @constructor.declaration

; Attributes/Annotations to ignore in skeleton map
(attribute_list) @annotation
