/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_CDS_HEAPSHARED_HPP
#define SHARE_CDS_HEAPSHARED_HPP

#include "cds/dumpTimeClassInfo.hpp"
#include "cds/metaspaceShared.hpp"
#include "classfile/compactHashtable.hpp"
#include "classfile/javaClasses.hpp"
#include "gc/shared/gc_globals.hpp"
#include "memory/allocation.hpp"
#include "memory/allStatic.hpp"
#include "oops/compressedOops.hpp"
#include "oops/oop.hpp"
#include "oops/oopHandle.hpp"
#include "oops/oopsHierarchy.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/resourceHash.hpp"

#if INCLUDE_CDS_JAVA_HEAP
class DumpedInternedStrings;
class FileMapInfo;
class KlassSubGraphInfo;
class KlassToOopHandleTable;
class ResourceBitMap;

struct ArchivableStaticFieldInfo;

// A dump time sub-graph info for Klass _k. It includes the entry points
// (static fields in _k's mirror) of the archived sub-graphs reachable
// from _k's mirror. It also contains a list of Klasses of the objects
// within the sub-graphs.
class KlassSubGraphInfo: public CHeapObj<mtClass> {
 private:
  // The class that contains the static field(s) as the entry point(s)
  // of archived object sub-graph(s).
  Klass* _k;
  // A list of classes need to be loaded and initialized before the archived
  // object sub-graphs can be accessed at runtime.
  GrowableArray<Klass*>* _subgraph_object_klasses;
  // A list of _k's static fields as the entry points of archived sub-graphs.
  // For each entry field, it is a tuple of field_offset, field_value and
  // is_closed_archive flag.
  GrowableArray<int>* _subgraph_entry_fields;

  // Does this KlassSubGraphInfo belong to the archived full module graph
  bool _is_full_module_graph;

  // Does this KlassSubGraphInfo references any classes that were loaded while
  // JvmtiExport::is_early_phase()!=true. If so, this KlassSubGraphInfo cannot be
  // used at runtime if JVMTI ClassFileLoadHook is enabled.
  bool _has_non_early_klasses;
  static bool is_non_early_klass(Klass* k);
  static void check_allowed_klass(InstanceKlass* ik);
 public:
  KlassSubGraphInfo(Klass* k, bool is_full_module_graph) :
    _k(k),  _subgraph_object_klasses(NULL),
    _subgraph_entry_fields(NULL),
    _is_full_module_graph(is_full_module_graph),
    _has_non_early_klasses(false) {}

  ~KlassSubGraphInfo() {
    if (_subgraph_object_klasses != NULL) {
      delete _subgraph_object_klasses;
    }
    if (_subgraph_entry_fields != NULL) {
      delete _subgraph_entry_fields;
    }
  };

  Klass* klass()            { return _k; }
  GrowableArray<Klass*>* subgraph_object_klasses() {
    return _subgraph_object_klasses;
  }
  GrowableArray<int>* subgraph_entry_fields() {
    return _subgraph_entry_fields;
  }
  void add_subgraph_entry_field(int static_field_offset, oop v,
                                bool is_closed_archive);
  void add_subgraph_object_klass(Klass *orig_k);
  int num_subgraph_object_klasses() {
    return _subgraph_object_klasses == NULL ? 0 :
           _subgraph_object_klasses->length();
  }
  bool is_full_module_graph() const { return _is_full_module_graph; }
  bool has_non_early_klasses() const { return _has_non_early_klasses; }
};

// An archived record of object sub-graphs reachable from static
// fields within _k's mirror. The record is reloaded from the archive
// at runtime.
class ArchivedKlassSubGraphInfoRecord {
 private:
  Klass* _k;
  bool _is_full_module_graph;
  bool _has_non_early_klasses;

  // contains pairs of field offset and value for each subgraph entry field
  Array<int>* _entry_field_records;

  // klasses of objects in archived sub-graphs referenced from the entry points
  // (static fields) in the containing class
  Array<Klass*>* _subgraph_object_klasses;
 public:
  ArchivedKlassSubGraphInfoRecord() :
    _k(NULL), _entry_field_records(NULL), _subgraph_object_klasses(NULL) {}
  void init(KlassSubGraphInfo* info);
  Klass* klass() const { return _k; }
  Array<int>* entry_field_records() const { return _entry_field_records; }
  Array<Klass*>* subgraph_object_klasses() const { return _subgraph_object_klasses; }
  bool is_full_module_graph() const { return _is_full_module_graph; }
  bool has_non_early_klasses() const { return _has_non_early_klasses; }
};
#endif // INCLUDE_CDS_JAVA_HEAP

struct LoadedArchiveHeapRegion;

class HeapShared: AllStatic {
  friend class VerifySharedOopClosure;

public:
  // Can this VM write heap regions into the CDS archive? Currently only G1+compressed{oops,cp}
  static bool can_write() {
    CDS_JAVA_HEAP_ONLY(
      if (_disable_writing) {
        return false;
      }
      return (UseG1GC && UseCompressedClassPointers);
    )
    NOT_CDS_JAVA_HEAP(return false;)
  }

  static void disable_writing() {
    CDS_JAVA_HEAP_ONLY(_disable_writing = true;)
  }

  static bool is_subgraph_root_class(InstanceKlass* ik);

  // Scratch objects for archiving Klass::java_mirror()
  static oop scratch_java_mirror(BasicType t) NOT_CDS_JAVA_HEAP_RETURN_(NULL);
  static oop scratch_java_mirror(Klass* k)    NOT_CDS_JAVA_HEAP_RETURN_(NULL);

private:
#if INCLUDE_CDS_JAVA_HEAP
  static bool _disable_writing;
  static DumpedInternedStrings *_dumped_interned_strings;
  static GrowableArrayCHeap<Metadata**, mtClassShared>* _native_pointers;

  // statistics
  constexpr static int ALLOC_STAT_SLOTS = 16;
  static size_t _alloc_count[ALLOC_STAT_SLOTS];
  static size_t _alloc_size[ALLOC_STAT_SLOTS];
  static size_t _total_obj_count;
  static size_t _total_obj_size; // in HeapWords

  static void count_allocation(size_t size);
  static void print_stats();
public:
  static unsigned oop_hash(oop const& p);
  static unsigned string_oop_hash(oop const& string) {
    return java_lang_String::hash_code(string);
  }

  struct CachedOopInfo {
    KlassSubGraphInfo* _subgraph_info;
    oop _referrer;
    oop _obj;
    CachedOopInfo() :_subgraph_info(), _referrer(), _obj() {}
  };

private:
  static void check_enum_obj(int level,
                             KlassSubGraphInfo* subgraph_info,
                             oop orig_obj,
                             bool is_closed_archive);

  typedef ResourceHashtable<oop, CachedOopInfo,
      36137, // prime number
      AnyObj::C_HEAP,
      mtClassShared,
      HeapShared::oop_hash> ArchivedObjectCache;
  static ArchivedObjectCache* _archived_object_cache;

  typedef ResourceHashtable<oop, oop,
      36137, // prime number
      AnyObj::C_HEAP,
      mtClassShared,
      HeapShared::oop_hash> OriginalObjectTable;
  static OriginalObjectTable* _original_object_table;

  class DumpTimeKlassSubGraphInfoTable
    : public ResourceHashtable<Klass*, KlassSubGraphInfo,
                               137, // prime number
                               AnyObj::C_HEAP,
                               mtClassShared,
                               DumpTimeSharedClassTable_hash> {
  public:
    int _count;
  };

public: // solaris compiler wants this for RunTimeKlassSubGraphInfoTable
  inline static bool record_equals_compact_hashtable_entry(
       const ArchivedKlassSubGraphInfoRecord* value, const Klass* key, int len_unused) {
    return (value->klass() == key);
  }

private:
  typedef OffsetCompactHashtable<
    const Klass*,
    const ArchivedKlassSubGraphInfoRecord*,
    record_equals_compact_hashtable_entry
    > RunTimeKlassSubGraphInfoTable;

  static DumpTimeKlassSubGraphInfoTable* _dump_time_subgraph_info_table;
  static RunTimeKlassSubGraphInfoTable _run_time_subgraph_info_table;

  static void check_closed_region_object(InstanceKlass* k);
  static CachedOopInfo make_cached_oop_info(oop orig_obj);
  static void archive_object_subgraphs(ArchivableStaticFieldInfo fields[],
                                       bool is_closed_archive,
                                       bool is_full_module_graph);

  // Archive object sub-graph starting from the given static field
  // in Klass k's mirror.
  static void archive_reachable_objects_from_static_field(
    InstanceKlass* k, const char* klass_name,
    int field_offset, const char* field_name,
    bool is_closed_archive);

  static void verify_subgraph_from_static_field(
    InstanceKlass* k, int field_offset) PRODUCT_RETURN;
  static void verify_reachable_objects_from(oop obj, bool is_archived) PRODUCT_RETURN;
  static void verify_subgraph_from(oop orig_obj) PRODUCT_RETURN;
  static void check_default_subgraph_classes();

  static KlassSubGraphInfo* init_subgraph_info(Klass *k, bool is_full_module_graph);
  static KlassSubGraphInfo* get_subgraph_info(Klass *k);

  static void init_subgraph_entry_fields(TRAPS) NOT_CDS_JAVA_HEAP_RETURN;
  static void init_subgraph_entry_fields(ArchivableStaticFieldInfo fields[], TRAPS);

  // UseCompressedOops only: Used by decode_from_archive
  static address _narrow_oop_base;
  static int     _narrow_oop_shift;

  // !UseCompressedOops only: used to relocate pointers to the archived objects
  static ptrdiff_t _runtime_delta;

  typedef ResourceHashtable<oop, bool,
      15889, // prime number
      AnyObj::C_HEAP,
      mtClassShared,
      HeapShared::oop_hash> SeenObjectsTable;

  static SeenObjectsTable *_seen_objects_table;

  // The "default subgraph" is the root of all archived objects that do not belong to any
  // of the classes defined in the <xxx>_archive_subgraph_entry_fields[] arrays:
  //    - interned strings
  //    - Klass::java_mirror()
  //    - ConstantPool::resolved_references()
  static KlassSubGraphInfo* _default_subgraph_info;

  static GrowableArrayCHeap<oop, mtClassShared>* _pending_roots;
  static OopHandle _roots;
  static OopHandle _scratch_basic_type_mirrors[T_VOID+1];
  static KlassToOopHandleTable* _scratch_java_mirror_table;

  static void init_seen_objects_table() {
    assert(_seen_objects_table == NULL, "must be");
    _seen_objects_table = new (mtClass)SeenObjectsTable();
  }
  static void delete_seen_objects_table() {
    assert(_seen_objects_table != NULL, "must be");
    delete _seen_objects_table;
    _seen_objects_table = NULL;
  }

  // Statistics (for one round of start_recording_subgraph ... done_recording_subgraph)
  static int _num_new_walked_objs;
  static int _num_new_archived_objs;
  static int _num_old_recorded_klasses;

  // Statistics (for all archived subgraphs)
  static int _num_total_subgraph_recordings;
  static int _num_total_walked_objs;
  static int _num_total_archived_objs;
  static int _num_total_recorded_klasses;
  static int _num_total_verifications;

  static void start_recording_subgraph(InstanceKlass *k, const char* klass_name,
                                       bool is_full_module_graph);
  static void done_recording_subgraph(InstanceKlass *k, const char* klass_name);

  static bool has_been_seen_during_subgraph_recording(oop obj);
  static void set_has_been_seen_during_subgraph_recording(oop obj);

  static void copy_roots();

  static void resolve_classes_for_subgraphs(JavaThread* current, ArchivableStaticFieldInfo fields[]);
  static void resolve_classes_for_subgraph_of(JavaThread* current, Klass* k);
  static void clear_archived_roots_of(Klass* k);
  static const ArchivedKlassSubGraphInfoRecord*
               resolve_or_init_classes_for_subgraph_of(Klass* k, bool do_init, TRAPS);
  static void resolve_or_init(Klass* k, bool do_init, TRAPS);
  static void init_archived_fields_for(Klass* k, const ArchivedKlassSubGraphInfoRecord* record);

  static int init_loaded_regions(FileMapInfo* mapinfo, LoadedArchiveHeapRegion* loaded_regions,
                                 MemRegion& archive_space);
  static void sort_loaded_regions(LoadedArchiveHeapRegion* loaded_regions, int num_loaded_regions,
                                  uintptr_t buffer);
  static bool load_regions(FileMapInfo* mapinfo, LoadedArchiveHeapRegion* loaded_regions,
                           int num_loaded_regions, uintptr_t buffer);
  static void init_loaded_heap_relocation(LoadedArchiveHeapRegion* reloc_info,
                                          int num_loaded_regions);
  static void fill_failed_loaded_region();
  static void mark_native_pointers(oop orig_obj, oop archived_obj);
  static void mark_one_native_pointer(oop archived_obj, int offset);
 public:
  static void reset_archived_object_states(TRAPS);
  static void create_archived_object_cache(bool create_orig_table) {
    _archived_object_cache =
      new (mtClass)ArchivedObjectCache();
    if (create_orig_table) {
      _original_object_table =
        new (mtClass)OriginalObjectTable();
    } else {
      _original_object_table = NULL;
    }
  }
  static void destroy_archived_object_cache() {
    delete _archived_object_cache;
    _archived_object_cache = NULL;
    if (_original_object_table != NULL) {
      delete _original_object_table;
      _original_object_table = NULL;
    }
  }
  static ArchivedObjectCache* archived_object_cache() {
    return _archived_object_cache;
  }
  static oop get_original_object(oop archived_object) {
    assert(_original_object_table != NULL, "sanity");
    oop* r = _original_object_table->get(archived_object);
    if (r == NULL) {
      return NULL;
    } else {
      return *r;
    }
  }

  static oop find_archived_heap_object(oop obj);
  static oop archive_object(oop obj);

  static void archive_java_mirrors();

  static void archive_objects(GrowableArray<MemRegion>* closed_regions,
                              GrowableArray<MemRegion>* open_regions);
  static void copy_closed_objects(GrowableArray<MemRegion>* closed_regions);
  static void copy_open_objects(GrowableArray<MemRegion>* open_regions);

  static oop archive_reachable_objects_from(int level,
                                            KlassSubGraphInfo* subgraph_info,
                                            oop orig_obj,
                                            bool is_closed_archive);

  static ResourceBitMap calculate_oopmap(MemRegion region); // marks all the oop pointers
  static ResourceBitMap calculate_ptrmap(MemRegion region); // marks all the native pointers
  static void add_to_dumped_interned_strings(oop string);

  // Scratch objects for archiving Klass::java_mirror()
  static void set_scratch_java_mirror(Klass* k, oop mirror);
  static void remove_scratch_objects(Klass* k);

  // We use the HeapShared::roots() array to make sure that objects stored in the
  // archived heap regions are not prematurely collected. These roots include:
  //
  //    - mirrors of classes that have not yet been loaded.
  //    - ConstantPool::resolved_references() of classes that have not yet been loaded.
  //    - ArchivedKlassSubGraphInfoRecords that have not been initialized
  //    - java.lang.Module objects that have not yet been added to the module graph
  //
  // When a mirror M becomes referenced by a newly loaded class K, M will be removed
  // from HeapShared::roots() via clear_root(), and K will be responsible for
  // keeping M alive.
  //
  // Other types of roots are also cleared similarly when they become referenced.

  // Dump-time only. Returns the index of the root, which can be used at run time to read
  // the root using get_root(index, ...).
  static int append_root(oop obj);

  // Dump-time and runtime
  static objArrayOop roots();
  static oop get_root(int index, bool clear=false);

  // Run-time only
  static void clear_root(int index);

  static void setup_test_class(const char* test_class_name) PRODUCT_RETURN;
#endif // INCLUDE_CDS_JAVA_HEAP

 public:
  static void init_scratch_objects(TRAPS) NOT_CDS_JAVA_HEAP_RETURN;
  static void run_full_gc_in_vm_thread() NOT_CDS_JAVA_HEAP_RETURN;

  static bool is_heap_region(int idx) {
    CDS_JAVA_HEAP_ONLY(return (idx >= MetaspaceShared::first_closed_heap_region &&
                               idx <= MetaspaceShared::last_open_heap_region);)
    NOT_CDS_JAVA_HEAP_RETURN_(false);
  }

  static bool is_archived_object_during_dumptime(oop p) NOT_CDS_JAVA_HEAP_RETURN_(false);

  static void resolve_classes(JavaThread* current) NOT_CDS_JAVA_HEAP_RETURN;
  static void initialize_from_archived_subgraph(JavaThread* current, Klass* k) NOT_CDS_JAVA_HEAP_RETURN;

  static void init_for_dumping(TRAPS) NOT_CDS_JAVA_HEAP_RETURN;
  static void write_subgraph_info_table() NOT_CDS_JAVA_HEAP_RETURN;
  static void serialize_root(SerializeClosure* soc) NOT_CDS_JAVA_HEAP_RETURN;
  static void serialize_tables(SerializeClosure* soc) NOT_CDS_JAVA_HEAP_RETURN;
  static bool initialize_enum_klass(InstanceKlass* k, TRAPS) NOT_CDS_JAVA_HEAP_RETURN_(false);

  // Returns the address of a heap object when it's mapped at the
  // runtime requested address. See comments in archiveBuilder.hpp.
  static address to_requested_address(address dumptime_addr) NOT_CDS_JAVA_HEAP_RETURN_(NULL);
  static oop to_requested_address(oop dumptime_oop) {
    return cast_to_oop(to_requested_address(cast_from_oop<address>(dumptime_oop)));
  }
  static bool is_a_test_class_in_unnamed_module(Klass* ik) NOT_CDS_JAVA_HEAP_RETURN_(false);
};

#if INCLUDE_CDS_JAVA_HEAP
class DumpedInternedStrings :
  public ResourceHashtable<oop, bool,
                           15889, // prime number
                           AnyObj::C_HEAP,
                           mtClassShared,
                           HeapShared::string_oop_hash>
{};
#endif

#endif // SHARE_CDS_HEAPSHARED_HPP
