package org.jabref.model.entry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javafx.beans.Observable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

import org.jabref.architecture.AllowedToUseLogic;
import org.jabref.logic.bibtex.FileFieldWriter;
import org.jabref.logic.importer.util.FileFieldParser;
import org.jabref.model.FieldChange;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.entry.event.EntriesEventSource;
import org.jabref.model.entry.event.FieldAddedOrRemovedEvent;
import org.jabref.model.entry.event.FieldChangedEvent;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;
import org.jabref.model.entry.field.OrFields;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.identifier.DOI;
import org.jabref.model.entry.identifier.ISBN;
import org.jabref.model.entry.types.EntryType;
import org.jabref.model.entry.types.IEEETranEntryType;
import org.jabref.model.entry.types.StandardEntryType;
import org.jabref.model.strings.LatexToUnicodeAdapter;
import org.jabref.model.strings.StringUtil;
import org.jabref.model.util.MultiKeyMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.EventBus;
import com.tobiasdiez.easybind.EasyBind;
import com.tobiasdiez.easybind.optional.OptionalBinding;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Represents a Bib(La)TeX entry, which can be BibTeX or BibLaTeX.
///
/// Example:
///
///     Some commment={fieldValue},otherFieldName ={otherFieldValue}
///
/// Then,
///
/// - "Some comment" is the comment before the entry,
/// - "misc" is the entry type
/// - "key" the citation key
/// - "fieldName" and "otherFieldName" the fields of the BibEntry
///
/// A BibTeX entry has following properties:
///
/// - comments before entry
/// - entry type
/// - citation key
/// - fields
///
/// In JabRef, this is modeled the following way:
///
/// - comments before entry --&gt; [#commentsBeforeEntry]
/// - entry type --&gt; [#type]
/// - citation key --&gt; contained in [#fields] using they hashmap key `KEY_FIELD`
/// - fields --&gt; contained in [#fields]
///
/// In case you search for a builder as described in Item 2 of the book "Effective Java", you won't find one. Please use the methods [#withCitationKey(String)] and [#withField(Field,String)]. All these methods set [#hasChanged()] to <code>false</code>. In case <code>changed</code>, use [#withChanged(boolean)].
///
@AllowedToUseLogic("because it needs access to parser and writers")
public class BibEntry {

    public static final EntryType DEFAULT_TYPE = StandardEntryType.Misc;
    private static final Logger LOGGER = LoggerFactory.getLogger(BibEntry.class);
    private final SharedBibEntryData sharedBibEntryData;

    /**
     * Map to store the words in every field
     */
    private final Map<Field, Set<String>> fieldsAsWords = new HashMap<>();

    /**
     * Cache that stores latex free versions of fields.
     */
    private final Map<Field, String> latexFreeFields = new ConcurrentHashMap<>();

    /**
     * Cache that stores the field as keyword lists (format &lt;Field, Separator, Keyword list>)
     */
    private final MultiKeyMap<StandardField, Character, KeywordList> fieldsAsKeywords = new MultiKeyMap<>(StandardField.class);

    private final EventBus eventBus = new EventBus();

    private String id;

    private final ObjectProperty<EntryType> type = new SimpleObjectProperty<>(DEFAULT_TYPE);

    private ObservableMap<Field, String> fields = FXCollections.observableMap(new ConcurrentHashMap<>());

    /**
     * The part before the start of the entry
     */
    private String commentsBeforeEntry = "";

    /**
     * Stores the text "rendering" of the entry as read by the BibTeX reader. Includes comments.
     */
    private String parsedSerialization = "";

    /**
     * Marks whether the complete serialization, which was read from file, should be used.
     * <p>
     * Is set to <code>true</code>, if parts of the entry changed. This causes the entry to be serialized based on the internal state (and not based on the old serialization)
     */
    private boolean changed;

    /**
     * Constructs a new BibEntry. The internal ID is set to IdGenerator.next()
     */
    public BibEntry() {
        this(DEFAULT_TYPE);
    }

    public BibEntry(String citationKey) {
        this();
        this.setCitationKey(citationKey);
    }

    /**
     * Constructs a new BibEntry. The internal ID is set to IdGenerator.next()
     */
    public BibEntry(EntryType type) {
        this.id = IdGenerator.next();
        setType(type);
        this.sharedBibEntryData = new SharedBibEntryData();
    }

    public BibEntry(EntryType type, String citationKey) {
        this(type);
        this.setCitationKey(citationKey);
    }

    /**
     * Returns a copy of this entry.
     * This will set a new ID for the copied entry to be able to distinguish both copies.
     * Does <em>not</em> port the listeners.
     */
    public BibEntry(BibEntry other) {
        this(other.type.getValue());
        this.fields = FXCollections.observableMap(new ConcurrentHashMap<>(other.fields));
        this.commentsBeforeEntry = other.commentsBeforeEntry;
        this.parsedSerialization = other.parsedSerialization;
        this.changed = other.changed;
    }

    public Optional<FieldChange> setMonth(Month parsedMonth) {
        return setField(StandardField.MONTH, parsedMonth.getJabRefFormat());
    }

    public Optional<FieldChange> setLangid(Langid parsedLangid) {
        return setField(StandardField.LANGUAGEID, parsedLangid.getJabRefFormat());
    }

    public Optional<String> getResolvedFieldOrAlias(OrFields fields, BibDatabase database) {
        for (Field field : fields.getFields()) {
            Optional<String> value = getResolvedFieldOrAlias(field, database);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /**
     * Map an (empty) field of a BibEntry to a field of a cross-referenced entry.
     *
     * @param targetField field name of the BibEntry
     * @param targetEntry type of the BibEntry
     * @param sourceEntry type of the cross-referenced BibEntry
     * @return the mapped field or null if there is no valid mapping available
     */
    private Optional<Field> getSourceField(Field targetField, EntryType targetEntry, EntryType sourceEntry) {
        // 1. Sort out forbidden fields
        if ((targetField == StandardField.IDS) ||
                (targetField == StandardField.CROSSREF) ||
                (targetField == StandardField.XREF) ||
                (targetField == StandardField.ENTRYSET) ||
                (targetField == StandardField.RELATED) ||
                (targetField == StandardField.SORTKEY)) {
            return Optional.empty();
        }

        // 2. Handle special field mappings
        if (((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.InBook)) ||
                ((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.BookInBook)) ||
                ((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.SuppBook)) ||
                ((sourceEntry == StandardEntryType.Book) && (targetEntry == StandardEntryType.InBook)) ||
                ((sourceEntry == StandardEntryType.Book) && (targetEntry == StandardEntryType.BookInBook)) ||
                ((sourceEntry == StandardEntryType.Book) && (targetEntry == StandardEntryType.SuppBook))) {
            if (targetField == StandardField.AUTHOR) {
                return Optional.of(StandardField.AUTHOR);
            }
            if (targetField == StandardField.BOOKAUTHOR) {
                return Optional.of(StandardField.AUTHOR);
            }
        }

        if (((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.Book)) ||
                ((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.InBook)) ||
                ((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.BookInBook)) ||
                ((sourceEntry == StandardEntryType.MvBook) && (targetEntry == StandardEntryType.SuppBook)) ||
                ((sourceEntry == StandardEntryType.MvCollection) && (targetEntry == StandardEntryType.Collection)) ||
                ((sourceEntry == StandardEntryType.MvCollection) && (targetEntry == StandardEntryType.InCollection)) ||
                ((sourceEntry == StandardEntryType.MvCollection) && (targetEntry == StandardEntryType.SuppCollection)) ||
                ((sourceEntry == StandardEntryType.MvProceedings) && (targetEntry == StandardEntryType.Proceedings)) ||
                ((sourceEntry == StandardEntryType.MvProceedings) && (targetEntry == StandardEntryType.InProceedings)) ||
                ((sourceEntry == StandardEntryType.MvReference) && (targetEntry == StandardEntryType.Reference)) ||
                ((sourceEntry == StandardEntryType.MvReference) && (targetEntry == StandardEntryType.InReference))) {
            if (targetField == StandardField.MAINTITLE) {
                return Optional.of(StandardField.TITLE);
            }
            if (targetField == StandardField.MAINSUBTITLE) {
                return Optional.of(StandardField.SUBTITLE);
            }
            if (targetField == StandardField.MAINTITLEADDON) {
                return Optional.of(StandardField.TITLEADDON);
            }

            // those fields are no more available for the same-name inheritance strategy
            if ((targetField == StandardField.TITLE) ||
                    (targetField == StandardField.SUBTITLE) ||
                    (targetField == StandardField.TITLEADDON)) {
                return Optional.empty();
            }

            // for these fields, inheritance is not allowed for the specified entry types
            if (targetField == StandardField.SHORTTITLE) {
                return Optional.empty();
            }
        }

        if (((sourceEntry == StandardEntryType.Book) && (targetEntry == StandardEntryType.InBook)) ||
                ((sourceEntry == StandardEntryType.Book) && (targetEntry == StandardEntryType.BookInBook)) ||
                ((sourceEntry == StandardEntryType.Book) && (targetEntry == StandardEntryType.SuppBook)) ||
                ((sourceEntry == StandardEntryType.Collection) && (targetEntry == StandardEntryType.InCollection)) ||
                ((sourceEntry == StandardEntryType.Collection) && (targetEntry == StandardEntryType.SuppCollection)) ||
                ((sourceEntry == StandardEntryType.Reference) && (targetEntry == StandardEntryType.InReference)) ||
                ((sourceEntry == StandardEntryType.Proceedings) && (targetEntry == StandardEntryType.InProceedings))) {
            if (targetField == StandardField.BOOKTITLE) {
                return Optional.of(StandardField.TITLE);
            }
            if (targetField == StandardField.BOOKSUBTITLE) {
                return Optional.of(StandardField.SUBTITLE);
            }
            if (targetField == StandardField.BOOKTITLEADDON) {
                return Optional.of(StandardField.TITLEADDON);
            }

            // those fields are no more available for the same-name inheritance strategy
            if ((targetField == StandardField.TITLE) ||
                    (targetField == StandardField.SUBTITLE) ||
                    (targetField == StandardField.TITLEADDON)) {
                return Optional.empty();
            }

            // for these fields, inheritance is not allowed for the specified entry types
            if (targetField == StandardField.SHORTTITLE) {
                return Optional.empty();
            }
        }

        if (((sourceEntry == IEEETranEntryType.Periodical) && (targetEntry == StandardEntryType.Article)) ||
                ((sourceEntry == IEEETranEntryType.Periodical) && (targetEntry == StandardEntryType.SuppPeriodical))) {
            if (targetField == StandardField.JOURNALTITLE) {
                return Optional.of(StandardField.TITLE);
            }
            if (targetField == StandardField.JOURNALSUBTITLE) {
                return Optional.of(StandardField.SUBTITLE);
            }

            // those fields are no more available for the same-name inheritance strategy
            if ((targetField == StandardField.TITLE) ||
                    (targetField == StandardField.SUBTITLE)) {
                return Optional.empty();
            }

            // for these fields, inheritance is not allowed for the specified entry types
            if (targetField == StandardField.SHORTTITLE) {
                return Optional.empty();
            }
        }

        // 3. Fallback to inherit the field with the same name.
        return Optional.ofNullable(targetField);
    }

    /**
     * Returns the text stored in the given field of the given bibtex entry
     * which belongs to the given database.
     * <p>
     * If a database is given, this function will try to resolve any string
     * references in the field-value.
     * Also, if a database is given, this function will try to find values for
     * unset fields in the entry linked by the "crossref" ({@link StandardField#CROSSREF} field, if any.
     *
     * @param field    The field to return the value of.
     * @param database The database of the bibtex entry.
     * @return The resolved field value or null if not found.
     */
    public Optional<String> getResolvedFieldOrAlias(Field field, @Nullable BibDatabase database) {
        return genericGetResolvedFieldOrAlias(field, database, BibEntry::getFieldOrAlias);
    }

    public Optional<String> getResolvedFieldOrAliasLatexFree(Field field, @Nullable BibDatabase database) {
        return genericGetResolvedFieldOrAlias(field, database, BibEntry::getFieldOrAliasLatexFree);
    }

    private Optional<String> genericGetResolvedFieldOrAlias(Field field, @Nullable BibDatabase database, BiFunction<BibEntry, Field, Optional<String>> getFieldOrAlias) {
        if ((InternalField.TYPE_HEADER == field) || (InternalField.OBSOLETE_TYPE_HEADER == field)) {
            return Optional.of(type.get().getDisplayName());
        }

        if (InternalField.KEY_FIELD == field) {
            return getCitationKey();
        }

        Optional<String> result = getFieldOrAlias.apply(this, field);
        // If this field is not set, and the entry has a crossref, try to look up the
        // field in the referred entry, following the biblatex rules
        if (result.isEmpty() && (database != null)) {
            Optional<BibEntry> referred = database.getReferencedEntry(this);
            if (referred.isPresent()) {
                EntryType sourceEntry = referred.get().type.get();
                EntryType targetEntry = type.get();
                Optional<Field> sourceField = getSourceField(field, targetEntry, sourceEntry);

                if (sourceField.isPresent()) {
                    result = getFieldOrAlias.apply(referred.get(), sourceField.get());
                }
            }
        }

        return (database == null) || result.isEmpty() ?
                result :
                Optional.of(database.resolveForStrings(result.get()));
    }

    /// Returns this entry's ID. It is used internally to distinguish different BibTeX entries.
    //  It is **not** the citation key (which is stored in the {@link InternalField#KEY_FIELD} and also known as BibTeX key).
    ///
    /// This id changes on each run of JabRef (because it is currently generated as increasing number).
    ///
    /// For more stable ids, check {@link org.jabref.model.entry.SharedBibEntryData#getSharedID}
    public String getId() {
        return id;
    }

    /**
     * Sets this entry's identifier (ID).
     * <p>
     * The entry is also updated in the shared database - provided the database containing it doesn't veto the change.
     *
     * @param id The ID to be used
     */
    @VisibleForTesting
    public void setId(String id) {
        Objects.requireNonNull(id, "Every BibEntry must have an ID");

        String oldId = this.id;

        eventBus.post(new FieldChangedEvent(this, InternalField.INTERNAL_ID_FIELD, id, oldId));
        this.id = id;
        changed = true;
    }

    /**
     * Sets the citation key. Note: This is <em>not</em> the internal Id of this entry.
     * The internal Id is always present, whereas the citation key might not be present.
     *
     * @param newKey The cite key to set. Must not be null; use {@link #clearCiteKey()} to remove the cite key.
     */
    public Optional<FieldChange> setCitationKey(String newKey) {
        return setField(InternalField.KEY_FIELD, newKey);
    }

    public BibEntry withCitationKey(String newKey) {
        setCitationKey(newKey);
        this.setChanged(false);
        return this;
    }

    /**
     * If not present, {@link BibEntry#getAuthorTitleYear(int)} can be used
     */
    public Optional<String> getCitationKey() {
        String key = fields.get(InternalField.KEY_FIELD);
        if (StringUtil.isBlank(key)) {
            return Optional.empty();
        } else {
            return Optional.of(key);
        }
    }

    public boolean hasCitationKey() {
        return getCitationKey().isPresent();
    }

    /**
     * Returns this entry's type.
     */
    public EntryType getType() {
        return type.getValue();
    }

    public ObjectProperty<EntryType> typeProperty() {
        return type;
    }

    /**
     * Sets this entry's type.
     */
    public Optional<FieldChange> setType(EntryType type) {
        return setType(type, EntriesEventSource.LOCAL);
    }

    /**
     * Sets this entry's type and sets the changed flag to true <br>
     * If the new entry type equals the old entry type no changed flag is set.
     */
    public Optional<FieldChange> setType(EntryType newType, EntriesEventSource eventSource) {
        Objects.requireNonNull(newType);

        EntryType oldType = type.get();
        if (newType.equals(oldType)) {
            return Optional.empty();
        }

        changed = true;
        this.type.setValue(newType);

        FieldChange change = new FieldChange(this, InternalField.TYPE_HEADER, oldType.getName(), newType.getName());
        eventBus.post(new FieldChangedEvent(change, eventSource));
        return Optional.of(change);
    }

    /**
     * Returns an unmodifiable sequence containing the names of all fields that are set for this particular entry.
     */
    public SequencedSet<Field> getFields() {
        return new LinkedHashSet<>(fields.keySet());
    }

    /**
     * Returns an unmodifiable sequence containing the names of all fields that are a) set for this particular entry and b) matching the given predicate
     */
    public SequencedSet<Field> getFields(Predicate<Field> selector) {
        return getFields().stream().filter(selector).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Returns the contents of the given field as an Optional.
     */
    public Optional<String> getField(Field field) {
        return Optional.ofNullable(fields.get(field));
    }

    public Optional<String> getFieldLatexFree(Field field) {
        if (InternalField.KEY_FIELD == field) {
            // the key field should not be converted
            return getCitationKey();
        } else if (InternalField.TYPE_HEADER == field) {
            return Optional.of(type.get().getDisplayName());
        } else if (latexFreeFields.containsKey(field)) {
            return Optional.ofNullable(latexFreeFields.get(field));
        } else {
            Optional<String> fieldValue = getField(field);
            if (fieldValue.isPresent()) {
                // TODO: Do we need FieldFactory.isLaTeXField(field) here to filter?
                String latexFreeValue = LatexToUnicodeAdapter.format(fieldValue.get()).intern();
                latexFreeFields.put(field, latexFreeValue);
                return Optional.of(latexFreeValue);
            } else {
                return Optional.empty();
            }
        }
    }

    /**
     * Returns true if the entry has the given field, or false if it is not set.
     */
    public boolean hasField(Field field) {
        return fields.containsKey(field);
    }

    /**
     * Internal method used to get the content of a field (or its alias)
     *
     * Used by {@link #getFieldOrAlias(Field)} and {@link #getFieldOrAliasLatexFree(Field)}
     *
     * @param field         the field
     * @param getFieldValue the method to get the value of a given field in a given entry
     * @return determined field value
     */
    private Optional<String> genericGetFieldOrAlias(Field field, BiFunction<BibEntry, Field, Optional<String>> getFieldValue) {
        Optional<String> fieldValue = getFieldValue.apply(this, field);

        if (fieldValue.isPresent() && !fieldValue.get().isEmpty()) {
            return fieldValue;
        }

        // No value of this field found, so look at the alias
        Field aliasForField = EntryConverter.FIELD_ALIASES.get(field);

        if (aliasForField != null) {
            return getFieldValue.apply(this, aliasForField);
        }

        // Finally, handle dates
        if (StandardField.DATE == field) {
            Optional<Date> date = Date.parse(
                    getFieldValue.apply(this, StandardField.YEAR),
                    getFieldValue.apply(this, StandardField.MONTH),
                    getFieldValue.apply(this, StandardField.DAY));

            return date.map(Date::getNormalized);
        }

        if ((StandardField.YEAR == field) || (StandardField.MONTH == field) || (StandardField.DAY == field)) {
            Optional<String> date = getFieldValue.apply(this, StandardField.DATE);
            if (date.isEmpty()) {
                return Optional.empty();
            }

            Optional<Date> parsedDate = Date.parse(date.get());
            if (parsedDate.isPresent()) {
                return switch (field) {
                    case StandardField.YEAR -> parsedDate.get().getYear().map(Object::toString);
                    case StandardField.MONTH -> parsedDate.get().getMonth().map(Month::getJabRefFormat);
                    case StandardField.DAY -> parsedDate.get().getDay().map(Object::toString);
                    default -> throw new IllegalStateException("Unexpected value");
                };
            } else {
                // Date field not in valid format
                LOGGER.debug("Could not parse date {}", date.get());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /// Returns the contents of the given field or its alias as an Optional
    ///
    /// The following aliases are considered (old bibtex <-> new biblatex) based
    /// on the biblatex documentation, chapter 2.2.5:
    ///
    /// - address        <-> location
    /// - annote         <-> annotation
    /// - archiveprefix  <-> eprinttype
    /// - journal        <-> journaltitle
    /// - key            <-> sortkey
    /// - pdf            <-> file
    /// - primaryclass   <-> eprintclass
    /// - school         <-> institution
    ///
    /// These work bidirectional.
    ///
    /// Special attention is paid to dates: (see the biblatex documentation,
    /// chapter 2.3.8)
    /// The fields 'year' and 'month' are used if the 'date'
    /// field is empty. Conversely, getFieldOrAlias("year") also tries to
    /// extract the year from the 'date' field (analogously for 'month').
    ///
    public Optional<String> getFieldOrAlias(Field field) {
        return genericGetFieldOrAlias(field, BibEntry::getField);
    }

    /**
     * Return the LaTeX-free contents of the given field or its alias an Optional
     * <p>
     * For details see also {@link #getFieldOrAlias(Field)}
     *
     * @param name the name of the field
     * @return the stored latex-free content of the field (or its alias)
     */
    public Optional<String> getFieldOrAliasLatexFree(Field name) {
        return genericGetFieldOrAlias(name, BibEntry::getFieldLatexFree);
    }

    /**
     * Sets a number of fields simultaneously. The given HashMap contains field
     * names as keys, each mapped to the value to set.
     */
    public void setField(Map<Field, String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");

        fields.forEach(this::setField);
    }

    /**
     * Set a field, and notify listeners about the change.
     *
     * @param field       The field to set
     * @param value       The value to set
     * @param eventSource Source the event is sent from
     */
    public Optional<FieldChange> setField(Field field, String value, EntriesEventSource eventSource) {
        Objects.requireNonNull(field, "field name must not be null");
        Objects.requireNonNull(value, "field value for field " + field.getName() + " must not be null");
        Objects.requireNonNull(eventSource, "field eventSource must not be null");

        if (value.isEmpty()) {
            return clearField(field);
        }

        String oldValue = getField(field).orElse(null);
        if (value.equals(oldValue)) {
            return Optional.empty();
        }

        boolean isNewField = oldValue == null;
        changed = true;

        invalidateFieldCache(field);
        fields.put(field, value.intern());

        FieldChange change = new FieldChange(this, field, oldValue, value);
        if (isNewField) {
            eventBus.post(new FieldAddedOrRemovedEvent(change, eventSource));
        } else {
            eventBus.post(new FieldChangedEvent(change, eventSource));
        }
        return Optional.of(change);
    }

    /**
     * Set a field, and notify listeners about the change.
     *
     * @param field The field to set.
     * @param value The value to set.
     */
    public Optional<FieldChange> setField(Field field, String value) {
        return setField(field, value, EntriesEventSource.LOCAL);
    }

    /**
     * Remove the mapping for the field name, and notify listeners about the change.
     *
     * @param field The field to clear.
     */
    public Optional<FieldChange> clearField(Field field) {
        return clearField(field, EntriesEventSource.LOCAL);
    }

    /**
     * Remove the mapping for the field name, and notify listeners about
     * the change including the {@link EntriesEventSource}.
     *
     * @param field       the field to clear.
     * @param eventSource the source a new {@link FieldChangedEvent} should be posten from.
     */
    public Optional<FieldChange> clearField(Field field, EntriesEventSource eventSource) {
        Optional<String> oldValue = getField(field);
        if (oldValue.isEmpty()) {
            return Optional.empty();
        }

        changed = true;

        invalidateFieldCache(field);
        fields.remove(field);

        FieldChange change = new FieldChange(this, field, oldValue.get(), null);
        eventBus.post(new FieldAddedOrRemovedEvent(change, eventSource));
        return Optional.of(change);
    }

    /**
     * Determines whether this entry has all the given fields present. If a non-null
     * database argument is given, this method will try to look up missing fields in
     * entries linked by the "crossref" field, if any.
     *
     * @param fields   An array of field names to be checked.
     * @param database The database in which to look up crossref'd entries, if any. This argument can be null, meaning
     *                 that no attempt will be made to follow crossrefs.
     * @return true if all fields are set or could be resolved, false otherwise.
     */
    public boolean allFieldsPresent(Collection<OrFields> fields, BibDatabase database) {
        return fields.stream().allMatch(field -> this.getResolvedFieldOrAlias(field, database).isPresent());
    }

    /// Serializes all fields, even the JabRef internal ones. Does NOT serialize "KEY_FIELD" as field, but as key.
    ///
    /// We do it this way to
    ///   a) enable debugging the internal representation and
    ///   b) save time at this method.
    ///
    ///
    /// This returns canonical BibTeX serialization. Special characters such as "{" or "&" are NOT escaped, but written
    /// as is. In case the JabRef "hack" for distinguishing "field = value" and "field = {value}" (in .bib files) is
    /// used, it is output as "field = {#value#}", which may cause headaches in debugging.
    ///
    /// Alternative for some more readable output: [#getAuthorTitleYear(int)] or [#getKeyAuthorTitleYear(int)].
    ///
    /// @return A user-readable string NOT A VALID BibTeX string
    @Override
    public String toString() {
        return CanonicalBibEntry.getCanonicalRepresentation(this);
    }

    public String getAuthorTitleYear() {
        return getAuthorTitleYear(0);
    }

    public String getKeyAuthorTitleYear() {
        return getKeyAuthorTitleYear(0);
    }

    /**
     * Creates a short textual description of the entry in the format: <code>Author1, Author2: Title (Year)</code>
     *
     * If <code>0</code> is passed as <code>maxCharacters</code>, the description is not truncated.
     *
     * @param maxCharacters The maximum number of characters (additional
     *                      characters are replaced with "..."). Set to 0 to disable truncation.
     * @return A short textual description of the entry in the format:
     * Author1, Author2: Title (Year)
     */
    public String getAuthorTitleYear(int maxCharacters) {
        String authorField = getField(StandardField.AUTHOR).orElse("N/A");
        String titleField = getField(StandardField.TITLE).orElse("N/A");
        String yearField = getField(StandardField.YEAR).orElse("N/A");

        String formattedAuthors = AuthorList.fixAuthorLastNameOnlyCommas(authorField, false);
        String formattedTitle = LatexToUnicodeAdapter.format(titleField);

        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append(formattedAuthors)
                   .append(": \"")
                   .append(formattedTitle)
                   .append("\" (")
                   .append(yearField)
                   .append(')');

        return StringUtil.limitStringLength(textBuilder.toString(), maxCharacters);
    }

    public String getKeyAuthorTitleYear(int maxCharacters) {
        String citationKey = getCitationKey().orElse("N/A");
        String result = citationKey + " - " + getAuthorTitleYear(0);
        return StringUtil.limitStringLength(result, maxCharacters);
    }

    /**
     * Returns the title of the given BibTeX entry as an Optional.
     *
     * @return an Optional containing the title of a BibTeX entry in case it exists, otherwise return an empty Optional.
     */
    public Optional<String> getTitle() {
        return getField(StandardField.TITLE);
    }

    public Optional<DOI> getDOI() {
        return getField(StandardField.DOI).flatMap(DOI::parse);
    }

    public Optional<ISBN> getISBN() {
        return getField(StandardField.ISBN).flatMap(ISBN::parse);
    }

    /**
     * Will return the publication date of the given bibtex entry conforming to ISO 8601, i.e. either YYYY or YYYY-MM.
     *
     * @return will return the publication date of the entry or null if no year was found.
     */
    public Optional<Date> getPublicationDate() {
        return getFieldOrAlias(StandardField.DATE).flatMap(Date::parse);
    }

    public String getParsedSerialization() {
        return parsedSerialization;
    }

    public void setParsedSerialization(String parsedSerialization) {
        changed = false;
        this.parsedSerialization = parsedSerialization;
    }

    public void setCommentsBeforeEntry(String parsedComments) {
        this.commentsBeforeEntry = parsedComments;
    }

    public boolean hasChanged() {
        return changed;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    /**
     * Required to trigger new serialization of the entry.
     * Reason: We don't have a <code>build()</code> command, we don't want to create a new serialization at each call,
     * we need to construct a BibEntry with <code>changed=false</code> (which is the default) and thus we need a workaround.
     */
    public BibEntry withChanged(boolean changed) {
        this.changed = changed;
        return this;
    }

    public Optional<FieldChange> putKeywords(List<String> keywords, Character delimiter) {
        Objects.requireNonNull(delimiter);
        return putKeywords(new KeywordList(keywords), delimiter);
    }

    public Optional<FieldChange> putKeywords(KeywordList keywords, Character delimiter) {
        Objects.requireNonNull(keywords);
        Optional<String> oldValue = this.getField(StandardField.KEYWORDS);

        if (keywords.isEmpty()) {
            // Clear keyword field
            if (oldValue.isPresent()) {
                return this.clearField(StandardField.KEYWORDS);
            } else {
                return Optional.empty();
            }
        }

        // Set new keyword field
        String newValue = keywords.getAsString(delimiter);
        return this.setField(StandardField.KEYWORDS, newValue);
    }

    /**
     * Check if a keyword already exists (case insensitive), if not: add it
     *
     * @param keyword Keyword to add
     */
    public void addKeyword(String keyword, Character delimiter) {
        Objects.requireNonNull(keyword, "keyword must not be null");

        if (keyword.isEmpty()) {
            return;
        }

        addKeyword(new Keyword(keyword), delimiter);
    }

    public void addKeyword(Keyword keyword, Character delimiter) {
        KeywordList keywords = this.getKeywords(delimiter);
        keywords.add(keyword);
        this.putKeywords(keywords, delimiter);
    }

    /**
     * Add multiple keywords to entry
     *
     * @param keywords Keywords to add
     */
    public void addKeywords(Collection<String> keywords, Character delimiter) {
        Objects.requireNonNull(keywords);
        keywords.forEach(keyword -> addKeyword(keyword, delimiter));
    }

    public KeywordList getKeywords(Character delimiter) {
        return getFieldAsKeywords(StandardField.KEYWORDS, delimiter);
    }

    public KeywordList getResolvedKeywords(Character delimiter, BibDatabase database) {
        Optional<String> keywordsContent = getResolvedFieldOrAlias(StandardField.KEYWORDS, database);
        return keywordsContent.map(content -> KeywordList.parse(content, delimiter)).orElse(new KeywordList());
    }

    public Optional<FieldChange> removeKeywords(KeywordList keywordsToRemove, Character keywordDelimiter) {
        KeywordList keywordList = getKeywords(keywordDelimiter);

        // We need to fix "file has changed on disk" for duplicate keywords
        // The input of the set may contain duplicate keywords (which are present as single keyword in the set)
        // Even if no "keywordsToRemove" is contained, the method "putKeywords" will return a change, because the duplicate keywords will have been removed
        int oldSize = keywordList.size();
        keywordList.removeAll(keywordsToRemove);
        // claim 1: The size of a set is the same, if no element is removed
        // claim 2: The size of a set is different if an element was removed
        // With claim 1, we can assume no change if there is no change on the size
        if (oldSize == keywordList.size()) {
            return Optional.empty();
        } else {
            return putKeywords(keywordList, keywordDelimiter);
        }
    }

    public Optional<FieldChange> replaceKeywords(KeywordList keywordsToReplace,
                                                 Keyword newValue,
                                                 Character keywordDelimiter) {
        KeywordList keywordList = getKeywords(keywordDelimiter);
        keywordList.replaceAll(keywordsToReplace, newValue);

        return putKeywords(keywordList, keywordDelimiter);
    }

    public Collection<String> getFieldValues() {
        return fields.values();
    }

    public Map<Field, String> getFieldMap() {
        return fields;
    }

    public SharedBibEntryData getSharedBibEntryData() {
        return sharedBibEntryData;
    }

    public BibEntry withSharedBibEntryData(int sharedId, int version) {
        sharedBibEntryData.setSharedID(sharedId);
        sharedBibEntryData.setVersion(version);
        return this;
    }

    public BibEntry withSharedBibEntryData(SharedBibEntryData sharedBibEntryData) {
        sharedBibEntryData = sharedBibEntryData;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if ((o == null) || (getClass() != o.getClass())) {
            return false;
        }
        BibEntry entry = (BibEntry) o;
        return Objects.equals(type.getValue(), entry.type.getValue())
                && Objects.equals(fields, entry.fields)
                && Objects.equals(commentsBeforeEntry, entry.commentsBeforeEntry);
    }

    /**
     * On purpose, this hashes the "content" of the BibEntry, not the {@link #sharedBibEntryData}.
     *
     * The content is
     *
     * <ul>
     *     <li>comments before entry</li>
     *     <li>entry type</li>
     *     <li>fields (including the citation key {@link InternalField#KEY_FIELD}</li>
     * </ul>
     */
    @Override
    public int hashCode() {
        return Objects.hash(type.getValue(), fields, commentsBeforeEntry);
    }

    public void registerListener(Object object) {
        this.eventBus.register(object);
    }

    public void unregisterListener(Object object) {
        try {
            this.eventBus.unregister(object);
        } catch (IllegalArgumentException e) {
            // occurs if the event source has not been registered, should not prevent shutdown
            LOGGER.debug("Problem unregistering", e);
        }
    }

    public BibEntry withField(Field field, String value) {
        setField(field, value);
        this.setChanged(false);
        return this;
    }

    /**
     * A copy is made of the parameter
     */
    public BibEntry withFields(Map<Field, String> content) {
        this.fields = FXCollections.observableMap(new HashMap<>(content));
        this.setChanged(false);
        return this;
    }

    public BibEntry withDate(Date date) {
        setDate(date);
        this.setChanged(false);
        return this;
    }

    public BibEntry withMonth(Month parsedMonth) {
        setMonth(parsedMonth);
        this.setChanged(false);
        return this;
    }

    /*
     * Returns user comments (arbitrary text before the entry), if they exist. If not, returns the empty String
     */
    public String getUserComments() {
        return commentsBeforeEntry;
    }

    public BibEntry withUserComments(String commentsBeforeEntry) {
        this.commentsBeforeEntry = commentsBeforeEntry;
        this.setChanged(false);
        return this;
    }

    public List<ParsedEntryLink> getEntryLinkList(Field field, BibDatabase database) {
        return getField(field).map(fieldValue -> EntryLinkList.parse(fieldValue, database))
                              .orElse(List.of());
    }

    public Optional<FieldChange> setEntryLinkList(Field field, List<ParsedEntryLink> list) {
        return setField(field, EntryLinkList.serialize(list));
    }

    public Set<String> getFieldAsWords(Field field) {
        Set<String> storedList = fieldsAsWords.get(field);
        if (storedList != null) {
            return storedList;
        } else {
            String fieldValue = fields.get(field);
            if (fieldValue == null) {
                return Set.of();
            } else {
                HashSet<String> words = new HashSet<>(StringUtil.getStringAsWords(fieldValue));
                fieldsAsWords.put(field, words);
                return words;
            }
        }
    }

    public KeywordList getFieldAsKeywords(Field field, Character keywordSeparator) {
        if (field instanceof StandardField standardField) {
            Optional<KeywordList> storedList = fieldsAsKeywords.get(standardField, keywordSeparator);
            if (storedList.isPresent()) {
                return storedList.get();
            }
        }

        KeywordList keywords = getField(field)
                .map(content -> KeywordList.parse(content, keywordSeparator))
                .orElse(new KeywordList());

        if (field instanceof StandardField standardField) {
            fieldsAsKeywords.put(standardField, keywordSeparator, keywords);
        }
        return keywords;
    }

    public Optional<FieldChange> clearCiteKey() {
        return clearField(InternalField.KEY_FIELD);
    }

    private void invalidateFieldCache(Field field) {
        latexFreeFields.remove(field);
        fieldsAsWords.remove(field);

        if (field instanceof StandardField standardField) {
            fieldsAsKeywords.remove(standardField);
        }
    }

    // region files
    public Optional<FieldChange> setFiles(List<LinkedFile> files) {
        Optional<String> oldValue = this.getField(StandardField.FILE);
        String newValue = FileFieldWriter.getStringRepresentation(files);

        if (oldValue.isPresent() && oldValue.get().equals(newValue)) {
            return Optional.empty();
        }

        return this.setField(StandardField.FILE, newValue);
    }

    public BibEntry withFiles(List<LinkedFile> files) {
        setFiles(files);
        this.setChanged(false);
        return this;
    }

    /**
     * Gets a list of linked files.
     *
     * @return the list of linked files, is never null but can be empty.
     * Changes to the underlying list will have no effect on the entry itself. Use {@link #addFile(LinkedFile)}.
     */
    public List<LinkedFile> getFiles() {
        Optional<String> oldValue = getField(StandardField.FILE);
        if (oldValue.isEmpty()) {
            return new ArrayList<>(); // Return new ArrayList because emptyList is immutable
        }

        // Extract the path
        return FileFieldParser.parse(oldValue.get());
    }

    public Optional<FieldChange> addFile(LinkedFile file) {
        List<LinkedFile> linkedFiles = getFiles();
        linkedFiles.add(file);
        return setFiles(linkedFiles);
    }

    public Optional<FieldChange> addFile(int index, LinkedFile file) {
        List<LinkedFile> linkedFiles = getFiles();
        linkedFiles.add(index, file);
        return setFiles(linkedFiles);
    }

    public Optional<FieldChange> addFiles(List<LinkedFile> filesToAdd) {
        if (filesToAdd.isEmpty()) {
            return Optional.empty();
        }
        if (this.getField(StandardField.FILE).isEmpty()) {
            return setFiles(filesToAdd);
        }
        List<LinkedFile> currentFiles = getFiles();
        currentFiles.addAll(filesToAdd);
        return setFiles(currentFiles);
    }
    // endregion

    /**
     * Checks {@link StandardField#CITES} for a list of citation keys and returns them.
     * <p>
     * Empty citation keys are not returned. There is no consistency check made.
     *
     * @return List of citation keys; empty list if field is empty or not available.
     */
    public SequencedSet<String> getCites() {
        return this.getField(StandardField.CITES)
                   .map(content -> Arrays.stream(content.split(",")))
                   .orElseGet(Stream::empty)
                   .map(String::trim)
                   .filter(key -> !key.isEmpty())
                   .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Optional<FieldChange> setCites(SequencedSet<String> keys) {
        return this.setField(StandardField.CITES, keys.stream().collect(Collectors.joining(",")));
    }

    public void setDate(Date date) {
        date.getYear().ifPresent(year -> setField(StandardField.YEAR, year.toString()));
        date.getMonth().ifPresent(this::setMonth);
        date.getDay().ifPresent(day -> setField(StandardField.DAY, day.toString()));
    }

    public Optional<Month> getMonth() {
        return getFieldOrAlias(StandardField.MONTH).flatMap(Month::parse);
    }

    public Optional<Season> getYearDivision() {
        return getFieldOrAlias(StandardField.YEARDIVISION).flatMap(Season::parse);
    }

    public OptionalBinding<String> getFieldBinding(Field field) {
        if ((field == InternalField.TYPE_HEADER) || (field == InternalField.OBSOLETE_TYPE_HEADER)) {
            return EasyBind.wrapNullable(type).mapOpt(EntryType::getDisplayName);
        }
        return EasyBind.valueAt(fields, field);
    }

    public OptionalBinding<String> getCiteKeyBinding() {
        return getFieldBinding(InternalField.KEY_FIELD);
    }

    public ObservableMap<Field, String> getFieldsObservable() {
        return fields;
    }

    /**
     * Returns a list of observables that represent the data of the entry.
     */
    public Observable[] getObservables() {
        return new Observable[] {fields, type};
    }

    /**
     * Helper method to add a downloaded file to the entry.
     * <p>
     * Use-case: URL is contained in the file, the file is downloaded and should then replace the url.
     * This method. adds the given path (as file) to the entry and removes the url.
     *
     * @param linkToDownloadedFile the link to the file, which was downloaded
     * @param downloadedFile       the path to be added to the entry
     */
    public void replaceDownloadedFile(String linkToDownloadedFile, LinkedFile downloadedFile) {
        List<LinkedFile> linkedFiles = this.getFiles();

        int oldFileIndex = -1;
        int i = 0;
        while ((i < linkedFiles.size()) && (oldFileIndex == -1)) {
            LinkedFile file = linkedFiles.get(i);
            // The file type changes as part of download process (see prepareDownloadTask), thus we only compare by link
            if (file.getLink().equalsIgnoreCase(linkToDownloadedFile)) {
                oldFileIndex = i;
            }
            i++;
        }
        if (oldFileIndex == -1) {
            linkedFiles.addFirst(downloadedFile);
        } else {
            linkedFiles.set(oldFileIndex, downloadedFile);
        }

        this.setFiles(linkedFiles);
    }

    /**
     * Merge this entry's fields with another BibEntry. Non-intersecting fields will be automatically merged. In cases of
     * intersection, priority is given to THIS entry's field value.
     *
     * @param other another BibEntry from which fields are sourced from
     */
    public void mergeWith(BibEntry other) {
        mergeWith(other, Set.of());
    }

    /**
     * Merge this entry's fields with another BibEntry. Non-intersecting fields will be automatically merged. In cases of
     * intersection, priority is given to THIS entry's field value, UNLESS specified otherwise in the arguments.
     *
     * @param other                  another BibEntry from which fields are sourced from
     * @param otherPrioritizedFields collection of Fields in which 'other' has a priority into final result
     */
    public void mergeWith(BibEntry other, Set<Field> otherPrioritizedFields) {
        Set<Field> thisFields = new TreeSet<>(Comparator.comparing(Field::getName));
        Set<Field> otherFields = new TreeSet<>(Comparator.comparing(Field::getName));

        thisFields.addAll(this.getFields());
        otherFields.addAll(other.getFields());

        // At the moment, "Field" interface does not provide explicit equality, so using their names instead.
        Set<String> thisFieldsNames = thisFields.stream().map(Field::getName).collect(Collectors.toSet());
        Set<String> otherPrioritizedFieldsNames = otherPrioritizedFields.stream().map(Field::getName).collect(Collectors.toSet());

        for (Field otherField : otherFields) {
            Optional<String> otherFieldValue = other.getField(otherField);
            if (!thisFieldsNames.contains(otherField.getName()) ||
                    otherPrioritizedFieldsNames.contains(otherField.getName())) {
                // As iterator only goes through non-null fields from OTHER, otherFieldValue can never be empty
                otherFieldValue.ifPresent(s -> this.setField(otherField, s));
            } else {
                switch (otherField) {
                    case StandardField.FILE -> {
                        List<LinkedFile> currentFiles = this.getFiles();
                        List<LinkedFile> otherFiles = other.getFiles();
                        List<LinkedFile> filesToAdd = otherFiles.stream()
                                                                .filter(file -> !currentFiles.contains(file))
                                                                .toList();
                        if (!filesToAdd.isEmpty()) {
                            this.addFiles(filesToAdd);
                        }
                    }
                    // TODO: Merging of keywords
                    default -> {
                        // We keep the data of the current BibEntry
                    }
                }
            }
        }

        if (this.getType().equals(DEFAULT_TYPE)) {
            this.setType(other.getType());
        }
    }

    public boolean isEmpty() {
        if (this.fields.isEmpty()) {
            return true;
        }
        return StandardField.AUTOMATIC_FIELDS.containsAll(this.getFields());
    }
}
