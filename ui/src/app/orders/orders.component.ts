import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OrderService, Order, OrderSearchParams, OrderEvent } from '../order.service';

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './orders.component.html',
  styleUrl: './orders.component.scss'
})
export class OrdersComponent implements OnInit {
  orders: Order[] = [];
  loading: boolean = false;
  error: string | null = null;
  
  // Track expanded order groups
  expandedGroups: Set<string> = new Set();
  groupOrders: Map<string, Order[]> = new Map();
  loadingGroups: Set<string> = new Set();
  
  // Track order events modal
  showEventsModal: boolean = false;
  selectedOrderEvents: OrderEvent[] = [];
  loadingEvents: boolean = false;
  loadingEventsForOrder: Set<string> = new Set();
  
  // Search filters
  searchParams: OrderSearchParams = {
    page: 0,
    size: 50
  };
  
  // Filter form fields
  accountNumber: string = '';
  symbol: string = '';
  fixClOrdId: string = '';
  fixOrderId: string = '';
  ordStatus: string = '';
  execType: string = '';
  isCopyOrder: boolean | null = null;
  
  // Status options
  ordStatusOptions = [
    { value: '', label: 'All' },
    { value: '0', label: 'New' },
    { value: '1', label: 'Partially Filled' },
    { value: '2', label: 'Filled' },
    { value: '4', label: 'Canceled' },
    { value: '5', label: 'Replaced' },
    { value: '8', label: 'Rejected' }
  ];
  
  execTypeOptions = [
    { value: '', label: 'All' },
    { value: '0', label: 'New' },
    { value: '1', label: 'Partial Fill' },
    { value: '2', label: 'Fill' },
    { value: '4', label: 'Canceled' },
    { value: '5', label: 'Replaced' },
    { value: '8', label: 'Rejected' }
  ];

  constructor(private orderService: OrderService) {}

  ngOnInit() {
    this.searchOrders();
  }

  searchOrders() {
    this.loading = true;
    this.error = null;
    
    // Build search params
    const params: OrderSearchParams = {
      page: this.searchParams.page || 0,
      size: this.searchParams.size || 50
    };
    
    if (this.accountNumber.trim()) {
      params.accountNumber = this.accountNumber.trim();
    }
    if (this.symbol.trim()) {
      params.symbol = this.symbol.trim();
    }
    if (this.fixClOrdId.trim()) {
      params.fixClOrdId = this.fixClOrdId.trim();
    }
    if (this.fixOrderId.trim()) {
      params.fixOrderId = this.fixOrderId.trim();
    }
    if (this.ordStatus) {
      params.ordStatus = this.ordStatus;
    }
    if (this.execType) {
      params.execType = this.execType;
    }
    if (this.isCopyOrder !== null) {
      params.isCopyOrder = this.isCopyOrder;
    }
    
    this.orderService.searchOrders(params).subscribe({
      next: (orders) => {
        this.orders = orders || [];
        this.loading = false;
        console.log(`Found ${this.orders.length} order(s)`);
      },
      error: (err) => {
        console.error('Error searching orders:', err);
        this.error = 'Error searching orders: ' + (err.message || 'Unknown error');
        this.loading = false;
        this.orders = [];
      }
    });
  }

  clearFilters() {
    this.accountNumber = '';
    this.symbol = '';
    this.fixClOrdId = '';
    this.fixOrderId = '';
    this.ordStatus = '';
    this.execType = '';
    this.isCopyOrder = null;
    this.searchParams.page = 0;
    this.searchOrders();
  }

  toggleOrderGroup(order: Order) {
    if (!order.primaryOrderClOrdId && !order.fixClOrdId) {
      return;
    }
    
    // Use primaryOrderClOrdId if available (for shadow orders), otherwise use fixClOrdId (for primary orders)
    const primaryClOrdId = order.primaryOrderClOrdId || order.fixClOrdId || '';
    
    if (this.expandedGroups.has(primaryClOrdId)) {
      // Collapse: remove from expanded set
      this.expandedGroups.delete(primaryClOrdId);
    } else {
      // Expand: add to expanded set and load group orders if not already loaded
      this.expandedGroups.add(primaryClOrdId);
      
      if (!this.groupOrders.has(primaryClOrdId)) {
        this.loadingGroups.add(primaryClOrdId);
        this.orderService.getOrdersByPrimaryClOrdId(primaryClOrdId).subscribe({
          next: (groupOrders) => {
            this.groupOrders.set(primaryClOrdId, groupOrders);
            this.loadingGroups.delete(primaryClOrdId);
            console.log(`Loaded ${groupOrders.length} order(s) for primary order ${primaryClOrdId}`);
          },
          error: (err) => {
            console.error('Error loading order group:', err);
            this.loadingGroups.delete(primaryClOrdId);
            this.expandedGroups.delete(primaryClOrdId);
          }
        });
      }
    }
  }
  
  isGroupExpanded(order: Order): boolean {
    const primaryClOrdId = order.primaryOrderClOrdId || order.fixClOrdId || '';
    return primaryClOrdId ? this.expandedGroups.has(primaryClOrdId) : false;
  }
  
  getGroupOrders(order: Order): Order[] {
    const primaryClOrdId = order.primaryOrderClOrdId || order.fixClOrdId || '';
    if (!primaryClOrdId) {
      return [];
    }
    return this.groupOrders.get(primaryClOrdId) || [];
  }
  
  isLoadingGroup(order: Order): boolean {
    const primaryClOrdId = order.primaryOrderClOrdId || order.fixClOrdId || '';
    return primaryClOrdId ? this.loadingGroups.has(primaryClOrdId) : false;
  }
  
  getDisplayOrders(): Array<Order & { isExpanded?: boolean; isGroupMember?: boolean; indentLevel?: number }> {
    const displayOrders: Array<Order & { isExpanded?: boolean; isGroupMember?: boolean; indentLevel?: number }> = [];
    const processedGroupIds = new Set<string>();
    const processedOrderIds = new Set<string>();
    
    for (const order of this.orders) {
      // Skip if already processed as a group member
      if (processedOrderIds.has(order.id || '')) {
        continue;
      }
      
      // Determine the primary ClOrdID for grouping (use fixClOrdId for primary orders, primaryOrderClOrdId for shadow orders)
      const primaryClOrdId = order.primaryOrderClOrdId || order.fixClOrdId || '';
      
      // Check if this order is part of an expanded group that we've already processed
      if (primaryClOrdId && processedGroupIds.has(primaryClOrdId)) {
        continue;
      }
      
      // Add the primary order
      displayOrders.push({ ...order, isExpanded: this.isGroupExpanded(order), isGroupMember: false, indentLevel: 0 });
      processedOrderIds.add(order.id || '');
      
      // If this order's group is expanded, add the group members (excluding orders already in the main list)
      if (primaryClOrdId && this.expandedGroups.has(primaryClOrdId)) {
        processedGroupIds.add(primaryClOrdId);
        const groupOrders = this.getGroupOrders(order);
        // Filter out orders that are already in the main search results
        const shadowOrders = groupOrders.filter(o => !processedOrderIds.has(o.id || ''));
        for (const shadowOrder of shadowOrders) {
          displayOrders.push({ ...shadowOrder, isExpanded: false, isGroupMember: true, indentLevel: 1 });
          processedOrderIds.add(shadowOrder.id || '');
        }
      }
    }
    
    return displayOrders;
  }

  getSideLabel(side: string | undefined, order?: Order): string {
    if (!side) return 'N/A';
    
    // Check if this is a locate order using the backend's isLocateOrder field
    const isLocate = order?.isLocateOrder === true;
    
    const sideMap: { [key: string]: string } = {
      '1': isLocate ? 'Buy (Locate)' : 'Buy',
      '2': 'Sell',
      '3': 'Sell Short',
      '4': 'Sell Short Exempt',
      '5': 'Buy (Locate)'
    };
    return sideMap[side] || side;
  }

  getOrdTypeLabel(ordType: string | undefined): string {
    if (!ordType) return 'N/A';
    const typeMap: { [key: string]: string } = {
      '1': 'Market',
      '2': 'Limit',
      '3': 'Stop',
      '4': 'Stop Limit'
    };
    return typeMap[ordType] || ordType;
  }

  getOrdStatusLabel(ordStatus: string | undefined): string {
    if (!ordStatus) return 'N/A';
    const statusMap: { [key: string]: string } = {
      '0': 'New',
      '1': 'Partially Filled',
      '2': 'Filled',
      '4': 'Canceled',
      '5': 'Replaced',
      '8': 'Rejected',
      'B': 'Locate Offer'
    };
    return statusMap[ordStatus] || ordStatus;
  }

  formatDateTime(dateTime: string | undefined): string {
    if (!dateTime) return 'N/A';
    try {
      const date = new Date(dateTime);
      // Format: M/d/yy, h:mm:ss.SSS AM/PM
      const month = date.getMonth() + 1;
      const day = date.getDate();
      const year = date.getFullYear().toString().slice(-2);
      const hours = date.getHours();
      const minutes = date.getMinutes().toString().padStart(2, '0');
      const seconds = date.getSeconds().toString().padStart(2, '0');
      const milliseconds = date.getMilliseconds().toString().padStart(3, '0');
      const ampm = hours >= 12 ? 'PM' : 'AM';
      const displayHours = hours % 12 || 12;
      
      return `${month}/${day}/${year}, ${displayHours}:${minutes}:${seconds}.${milliseconds} ${ampm}`;
    } catch (e) {
      return dateTime;
    }
  }

  viewOrderEvents(order: Order) {
    if (!order.id && !order.fixClOrdId) {
      console.warn('Order has no ID or ClOrdID, cannot load events');
      return;
    }
    
    this.showEventsModal = true;
    this.loadingEvents = true;
    this.selectedOrderEvents = [];
    
    const orderKey = order.id || order.fixClOrdId || '';
    this.loadingEventsForOrder.add(orderKey);
    
    this.orderService.getOrderEvents(order.id, order.fixClOrdId).subscribe({
      next: (events) => {
        this.selectedOrderEvents = events || [];
        this.loadingEvents = false;
        this.loadingEventsForOrder.delete(orderKey);
        console.log(`Loaded ${this.selectedOrderEvents.length} event(s) for order ${order.fixClOrdId}`);
      },
      error: (err) => {
        console.error('Error loading order events:', err);
        this.loadingEvents = false;
        this.loadingEventsForOrder.delete(orderKey);
        this.selectedOrderEvents = [];
      }
    });
  }

  closeEventsModal() {
    this.showEventsModal = false;
    this.selectedOrderEvents = [];
    this.loadingEvents = false;
  }

  isLoadingEvents(order: Order): boolean {
    const orderKey = order.id || order.fixClOrdId || '';
    return this.loadingEventsForOrder.has(orderKey);
  }

  getExecTypeLabel(execType: string | undefined): string {
    if (!execType) return 'N/A';
    const typeMap: { [key: string]: string } = {
      '0': 'New',
      '1': 'Partial Fill',
      '2': 'Fill',
      '4': 'Canceled',
      '5': 'Replaced',
      '8': 'Rejected'
    };
    return typeMap[execType] || execType;
  }
}

