// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <arrow/type_fwd.h>
#include <parquet/arrow/reader.h>
#include <stdint.h>

#include <memory>
#include <string>
#include <vector>

#include "common/status.h"
#include "exec/arrow/arrow_reader.h"
#include "io/fs/file_reader_writer_fwd.h"

namespace arrow {
class RecordBatch;
} // namespace arrow
namespace parquet {
class FileMetaData;
} // namespace parquet

namespace doris {

class RuntimeState;
class SlotDescriptor;
class TupleDescriptor;

// Reader of parquet file
class ParquetReaderWrap final : public ArrowReaderWrap {
public:
    // batch_size is not use here
    ParquetReaderWrap(RuntimeState* state, const std::vector<SlotDescriptor*>& file_slot_descs,
                      io::FileReaderSPtr file_reader, int32_t num_of_columns_from_file,
                      int64_t range_start_offset, int64_t range_size, bool case_sensitive = true);
    ~ParquetReaderWrap() override = default;

    Status size(int64_t* size) override;
    Status init_reader(const TupleDescriptor* tuple_desc, const std::string& timezone) override;
    Status init_parquet_type();

private:
    Status read_record_batch(bool* eof);

private:
    Status read_next_batch();
    void read_batches(arrow::RecordBatchVector& batches, int current_group) override;
    bool filter_row_group(int current_group) override;

private:
    // parquet file reader object
    std::shared_ptr<arrow::RecordBatch> _batch;
    std::unique_ptr<parquet::arrow::FileReader> _reader;
    std::shared_ptr<parquet::FileMetaData> _file_metadata;
    std::vector<arrow::Type::type> _parquet_column_type;

    int _rows_of_group; // rows in a group.
    int _current_line_of_group;
    int _current_line_of_batch;
    std::string _timezone;
};

} // namespace doris