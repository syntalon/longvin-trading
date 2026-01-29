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

  viewOrderGroup(order: Order) {
    if (order.orderGroupId) {
      this.orderService.getOrdersByGroup(order.orderGroupId).subscribe({
        next: (groupOrders) => {
          console.log('Order Group:', groupOrders);
          // You could show this in a modal or navigate to a detail page
          alert(`Order Group contains ${groupOrders.length} order(s)`);
        },
        error: (err) => {
          console.error('Error loading order group:', err);
        }
      });
    }
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

