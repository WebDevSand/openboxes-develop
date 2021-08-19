import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { AutoSizer, InfiniteLoader, List } from 'react-virtualized';
import _ from 'lodash';
import { connect } from 'react-redux';

import TableRow from './TableRow';

class TableBodyVirtualized extends Component {
  constructor(props) {
    super(props);

    this.rowRenderer = this.rowRenderer.bind(this);
    this.getRowHeight = this.getRowHeight.bind(this);
    this.bindListRef = this.bindListRef.bind(this);
    this.getHeight = this.getHeight.bind(this);
  }

  componentDidUpdate() {
    if (this.list) {
      this.list.recomputeRowHeights();
    }
  }

  getHeight() {
    const { fieldsConfig: { subfieldKey }, fields, properties } = this.props;
    const { totalCount } = properties;
    let height = 0;
    const maxTableHeight = window.innerHeight < 900 ?
      // 0.35 * window.innerHeight = 35vh from table-content class in StockMovement.scss
      0.35 * window.innerHeight : 0.40 * window.innerHeight;

    if (!subfieldKey) {
      if (totalCount * 28 > maxTableHeight) {
        height = maxTableHeight;
      } else if (totalCount > 0) {
        height = totalCount * 28;
      }
    } else {
      _.forEach(fields.value, (field) => {
        const subfields = field[subfieldKey];
        if (!height) {
          height = 28 * (subfields.length + 1);
        } else if (height + (28 * (subfields.length + 1)) > maxTableHeight) {
          height = maxTableHeight;
        } else {
          height += (28 * (subfields.length + 1));
        }
      });
    }

    return height || 28;
  }

  getRowHeight({ index }) {
    const { fieldsConfig: { subfieldKey, getDynamicRowAttr }, fields, properties } = this.props;

    if (!subfieldKey) {
      return 28;
    }

    const rowValues = fields.value ? fields.value[index] : null;
    const subfields = rowValues ? rowValues[subfieldKey] : null;

    if (!subfields) {
      return 28;
    }

    const dynamicAttr = getDynamicRowAttr ?
      getDynamicRowAttr({ ...properties, index, rowValues }) : {};

    if (dynamicAttr.hideSubfields) {
      return 28;
    }

    return 28 * (subfields.length + 1);
  }

  rowRenderer({
    key, index, style,
  }) {
    const {
      fieldsConfig, properties, fields, tableRef = () => {},
      addRow = (row = {}) => fields.push(row),
    } = this.props;
    const field = `${fields.name}[${index}]`;
    const RowComponent = fieldsConfig.rowComponent || TableRow;
    const { totalCount } = properties;

    if (fields.value[index]) {
      return (
        <div key={key} style={style}>
          <RowComponent
            field={field}
            index={index}
            properties={{
              ...properties,
              rowCount: totalCount,
            }}
            addRow={addRow}
            fieldsConfig={fieldsConfig}
            removeRow={() => fields.remove(index)}
            rowValues={fields.value[index]}
            rowRef={(el, fieldName) => tableRef(el, fieldName, index)}
          />
        </div>
      );
    }
    return (
      <div key={key} style={style}>
        Loading...
      </div>
    );
  }

  bindListRef(ref) {
    this.list = ref;
  }

  render() {
    // eslint-disable-next-line max-len
    const { properties, pageSize } = this.props;
    const {
      totalCount, loadMoreRows, isRowLoaded, isFirstPageLoaded,
    } = properties;

    const loadPage = isFirstPageLoaded ? () => {} : loadMoreRows;

    return (
      <div>
        <InfiniteLoader
          loadMoreRows={loadPage}
          isRowLoaded={isRowLoaded}
          rowCount={totalCount}
          minimumBatchSize={pageSize}
        >
          {({ onRowsRendered }) => (
            <AutoSizer disableHeight>
              {({ width }) => (
                <List
                  ref={this.bindListRef}
                  height={this.getHeight()}
                  onRowsRendered={onRowsRendered}
                  rowCount={totalCount}
                  rowHeight={this.getRowHeight}
                  rowRenderer={this.rowRenderer}
                  width={width}
                  props={properties}
                />
              )}
            </AutoSizer>
          )}
        </InfiniteLoader>
      </div>
    );
  }
}

const mapStateToProps = state => ({
  pageSize: state.session.pageSize,
});

export default connect(mapStateToProps, {})(TableBodyVirtualized);

TableBodyVirtualized.propTypes = {
  fieldsConfig: PropTypes.shape({
    getDynamicAttr: PropTypes.func,
  }).isRequired,
  fields: PropTypes.oneOfType([
    PropTypes.shape({}),
    PropTypes.arrayOf(PropTypes.shape({})),
  ]).isRequired,
  properties: PropTypes.shape({}).isRequired,
  addRow: PropTypes.func,
  tableRef: PropTypes.func,
  pageSize: PropTypes.number.isRequired,
};

TableBodyVirtualized.defaultProps = {
  addRow: undefined,
  tableRef: undefined,
};
