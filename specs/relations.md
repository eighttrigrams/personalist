# Relations

# Status Quo

Relations in Personalist are **unidirectional**, always, by design.
From any given Identity I claim that another Identity is related
in some way (bidirectional relations can be modelled accordingly by hand
by associating from both sides).

***Technical note.*** Since there exist no particular performance constrains either, the Relations
are stored (and versioned) as part of the Identities directly.

## Ordering

The Relations of an Identity are **ordered**, and the order is meant: it is the
Persona's ranking of which of the related Identities weigh more in the context of
this one. In the edit view they can be dragged into another order, and that order
stays volatile until Save — exactly as adding and removing one does, and committed
by the same new version.

***Technical note.*** The Relation list was already stored as an ordered array on
the Identity version, so a ranking needs no new storage and time-travels with
everything else: an older version keeps the order it was saved with. Over the API,
`PUT .../identities/:id` takes a `relation_order` of target ids beside
`relation_adds` and `relation_removes`. What it names comes first, in the order
given; what it does not name keeps its place after that, so two Relations can be
ranked without enumerating the rest. Saying nothing about the order — which is what
every plain edit says — leaves the ranking untouched.

This answers the *Ordered relations* consideration below, whose text is left
standing as it was written.

## Considerations for the future

### Relate outside a Persona

At some future point, Relations may point to different Personas'
Identities, too, and, in contrast to one's owns, even to older versions.

This fits well with the constraint of unidirectional relations.

### Ordered relations

Relations could be ordered, that is, there is a ranking which expresses
the Persona's view of which ones are more important concepts in context
of the original Identity.

On the other hand, I can let the text speak for itself. See also point below.

### Fat relations

Not sure if I want this. After all, the Identity's text can put a textual
description directly. See also point above.
