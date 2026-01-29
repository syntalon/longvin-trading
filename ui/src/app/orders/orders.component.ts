import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OrderService, Order, OrderSearchParams } from '../order.service';

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
    if (!order.orderGroupId) {
      return;
    }
    
    const groupId = order.orderGroupId;
    
    if (this.expandedGroups.has(groupId)) {
      // Collapse: remove from expanded set
      this.expandedGroups.delete(groupId);
    } else {
      // Expand: add to expanded set and load group orders if not already loaded
      this.expandedGroups.add(groupId);
      
      if (!this.groupOrders.has(groupId)) {
        this.loadingGroups.add(groupId);
        this.orderService.getOrdersByGroup(groupId).subscribe({
          next: (groupOrders) => {
            this.groupOrders.set(groupId, groupOrders);
            this.loadingGroups.delete(groupId);
            console.log(`Loaded ${groupOrders.length} order(s) for group ${groupId}`);
          },
          error: (err) => {
            console.error('Error loading order group:', err);
            this.loadingGroups.delete(groupId);
            this.expandedGroups.delete(groupId);
          }
        });
      }
    }
  }
  
  isGroupExpanded(order: Order): boolean {
    return order.orderGroupId ? this.expandedGroups.has(order.orderGroupId) : false;
  }
  
  getGroupOrders(order: Order): Order[] {
    if (!order.orderGroupId) {
      return [];
    }
    return this.groupOrders.get(order.orderGroupId) || [];
  }
  
  isLoadingGroup(order: Order): boolean {
    return order.orderGroupId ? this.loadingGroups.has(order.orderGroupId) : false;
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
      
      // Check if this order is part of an expanded group that we've already processed
      if (order.orderGroupId && processedGroupIds.has(order.orderGroupId)) {
        continue;
      }
      
      // Add the primary order
      displayOrders.push({ ...order, isExpanded: this.isGroupExpanded(order), isGroupMember: false, indentLevel: 0 });
      processedOrderIds.add(order.id || '');
      
      // If this order's group is expanded, add the group members (excluding orders already in the main list)
      if (order.orderGroupId && this.expandedGroups.has(order.orderGroupId)) {
        processedGroupIds.add(order.orderGroupId);
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

  getSideLabel(side: string | undefined): string {
    if (!side) return 'N/A';
    const sideMap: { [key: string]: string } = {
      '1': 'Buy',
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
}

